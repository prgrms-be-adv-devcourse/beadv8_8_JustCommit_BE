package com.justcommit.backend.product.review.infrastructure;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.justcommit.backend.product.image.application.ImageData;
import com.justcommit.backend.product.review.application.PlantIdentifier;
import com.justcommit.backend.product.review.application.ReviewEngineException;
import com.justcommit.backend.product.review.domain.Candidate;
import com.justcommit.backend.product.review.domain.PlantIdentification;
import com.justcommit.backend.product.review.domain.enums.SaleForm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

// Claude Haiku 식물 식별. 사진 전부와 금지 품종 목록을 넣고, 답은 structured output(JSON 형식 지정)으로 받음
// SDK가 429 · 5xx · 연결 실패를 2번 다시 시도함(요청마다 30초 타임아웃 → 최악 약 90초). 그래도 실패 · 거절 · 잘린 응답이면 ReviewEngineException
@Component
public class HaikuPlantIdentifier implements PlantIdentifier {

    static final String MODEL = "claude-haiku-5-5";
    private static final long MAX_TOKENS = 1024;

    private final AnthropicClient client;   // 키가 없으면 null (로컬 · 테스트에서 앱은 뜨고 검수만 관리자 확인으로)

    @Autowired
    public HaikuPlantIdentifier(@Value("${custom.review.anthropic.api-key:}") String apiKey) {
        this(StringUtils.hasText(apiKey)
                ? AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(2).timeout(Duration.ofSeconds(30)).build()
                : null);
    }

    HaikuPlantIdentifier(AnthropicClient client) {
        this.client = client;
    }

    @Override
    public boolean isConfigured() {
        return client != null;
    }

    @Override
    public PlantIdentification identify(List<ImageData> images, List<String> bannedNames) {
        if (client == null) {
            throw new ReviewEngineException("ANTHROPIC_API_KEY가 없어 식물 식별을 할 수 없음");
        }
        StructuredMessage<Answer> message;
        try {
            message = client.messages().create(request(images, bannedNames));
        } catch (RuntimeException e) {
            throw new ReviewEngineException("Haiku 요청 실패", e);
        }
        if (message.stopReason().map(r -> !r.equals(StopReason.END_TURN)).orElse(true)) {
            throw new ReviewEngineException("Haiku 응답이 끝나지 않음: " + message.stopReason().orElse(null));
        }
        Answer answer = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .findFirst()
                .orElseThrow(() -> new ReviewEngineException("Haiku 응답에 결과가 없음"));
        return toDomain(answer);
    }

    StructuredMessageCreateParams<Answer> request(List<ImageData> images, List<String> bannedNames) {
        List<ContentBlockParam> content = new ArrayList<>();
        for (ImageData image : images) {
            content.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.of(image.mediaType()))
                            .data(Base64.getEncoder().encodeToString(image.bytes()))
                            .build())
                    .build()));
        }
        content.add(ContentBlockParam.ofText(TextBlockParam.builder()
                .text("위 사진들은 중고 식물 마켓에 올라온 상품 하나의 사진입니다. 식물을 식별해 주세요.")
                .build()));
        return MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .system(systemPrompt(bannedNames))
                .addUserMessageOfContentBlockParams(content)
                .outputConfig(Answer.class)
                .build();
    }

    static String systemPrompt(List<String> bannedNames) {
        return """
                You identify plants in product photos for a Korean second-hand plant marketplace.
                - plant: false if the photos do not show a real plant for sale (people, objects, artificial plants, drawings).
                - saleForm: PLANT for a living rooted plant (potted, seedling, rooted cutting), SEED for seeds,
                  CUTTING for unrooted cuttings or loose leaves/stems for propagation, BULB for bulbs, tubers or corms not planted.
                - candidates: up to 3 species, most likely first. scientificName is the accepted binomial
                  (add var./subsp. only when you can see it). koreanName is the common Korean name.
                  confidence is your probability from 0 to 1. Return an empty list if you cannot tell.
                - These species are illegal to sell in Korea. If the plant may be one of them, include it as a candidate
                  with an honest confidence instead of leaving it out:
                %s
                """.formatted(String.join(", ", bannedNames));
    }

    static PlantIdentification toDomain(Answer answer) {
        if (answer.saleForm() == null && answer.plant()) {
            throw new ReviewEngineException("Haiku 응답에 판매 형태가 없음");
        }
        List<Candidate> candidates = answer.candidates() == null ? List.of() : answer.candidates().stream()
                .filter(c -> c.confidence() >= 0 && c.confidence() <= 1)
                .limit(3)
                .map(c -> new Candidate(c.scientificName(), c.koreanName(), c.confidence()))
                .toList();
        return new PlantIdentification(answer.plant(), answer.saleForm(), candidates);
    }

    // structured output 형식 (이 클래스에서 JSON schema를 만듦)
    record Answer(
            @JsonPropertyDescription("사진이 판매용 실제 식물이면 true") boolean plant,
            @JsonPropertyDescription("판매 형태") SaleForm saleForm,
            @JsonPropertyDescription("품종 후보 최대 3개, 확신도 높은 순") List<AnswerCandidate> candidates) {
    }

    record AnswerCandidate(
            @JsonPropertyDescription("학명") String scientificName,
            @JsonPropertyDescription("한글 이름") String koreanName,
            @JsonPropertyDescription("확신도 0~1") double confidence) {
    }
}
