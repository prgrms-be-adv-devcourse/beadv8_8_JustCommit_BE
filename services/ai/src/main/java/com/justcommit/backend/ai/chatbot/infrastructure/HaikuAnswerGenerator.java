package com.justcommit.backend.ai.chatbot.infrastructure;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.justcommit.backend.ai.chatbot.application.AnswerGenerator;
import com.justcommit.backend.ai.chatbot.application.ChatPrompt;
import com.justcommit.backend.ai.chatbot.application.ChatTurn;
import java.time.Duration;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 답변 생성: Claude Haiku 5.5 (product 사진 검수 HaikuPlantIdentifier와 같은 SDK · 모델 · 키, ADR 0031).
 * 재시도 없이 한 번만 부른다: Gateway 응답 20초 안에 임베딩(최대 3초) + LLM(timeout)이 끝나야 함.
 * 실패 · 거절 · 잘린 응답은 예외 → 서비스가 고정 문장 + 상품 카드로 답한다.
 */
@Component
public class HaikuAnswerGenerator implements AnswerGenerator {

    static final String MODEL = "claude-haiku-5-5";
    // Haiku 5.5는 생각(adaptive thinking)이 기본으로 켜져 있고 생각 토큰도 max_tokens에 들어감 → 한도는 넉넉히, 깊이는 LOW
    static final long MAX_TOKENS = 4096;

    private final AnthropicClient client;   // 키가 없으면 null (로컬 · 테스트에서 앱은 뜨고 답변만 고정 문장)

    @Autowired
    public HaikuAnswerGenerator(
            @Value("${custom.chatbot.anthropic.api-key:}") String apiKey,
            @Value("${custom.chatbot.anthropic.timeout:10s}") Duration timeout) {
        this(StringUtils.hasText(apiKey)
                ? AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(0).timeout(timeout).build()
                : null);
    }

    HaikuAnswerGenerator(AnthropicClient client) {
        this.client = client;
    }

    @Override
    public boolean isConfigured() {
        return client != null;
    }

    @Override
    public String answer(ChatPrompt prompt) {
        if (client == null) {
            throw new IllegalStateException("ANTHROPIC_API_KEY가 없어 답변을 만들 수 없음");
        }
        Message message = client.messages().create(request(prompt));
        if (message.stopReason().map(r -> !r.equals(StopReason.END_TURN)).orElse(true)) {
            throw new IllegalStateException("Haiku 응답이 끝나지 않음: " + message.stopReason().orElse(null));
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining()).strip();
        if (text.isEmpty()) {
            throw new IllegalStateException("Haiku 응답에 글이 없음");
        }
        return text;
    }

    static MessageCreateParams request(ChatPrompt prompt) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .system(prompt.system())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build());
        for (ChatTurn turn : prompt.history()) {
            if (turn.role() == ChatTurn.Role.USER) {
                builder.addUserMessage(turn.content());
            } else {
                builder.addAssistantMessage(turn.content());
            }
        }
        return builder.addUserMessage(prompt.userMessage()).build();
    }
}
