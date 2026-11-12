package com.justcommit.backend.product.review.infrastructure;

import com.justcommit.backend.product.image.application.ImageData;
import com.justcommit.backend.product.review.application.ModerationClient;
import com.justcommit.backend.product.review.application.ReviewEngineException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

// OpenAI Moderation (omni-moderation-latest). 사진은 한 요청에 한 장씩, 글은 제목 · 설명을 합쳐 한 번
// 429 · 5xx · 연결 실패는 잠깐 쉬고 다시 시도(모두 3번), 그래도 실패하거나 응답을 해석하지 못하면 ReviewEngineException
@Component
public class OpenAiModerationClient implements ModerationClient {

    static final String MODEL = "omni-moderation-latest";
    private static final int MAX_ATTEMPTS = 3;

    private final RestClient restClient;
    private final Duration backoff;

    public OpenAiModerationClient(RestClient.Builder builder,
                                  @Value("${custom.review.openai.base-url:https://api.openai.com}") String baseUrl,
                                  @Value("${custom.review.openai.api-key:}") String apiKey,
                                  @Value("${custom.review.retry-backoff:1s}") Duration backoff) {
        this.restClient = builder.baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.backoff = backoff;
    }

    @Override
    public boolean isImageFlagged(ImageData image) {
        String dataUrl = "data:" + image.mediaType() + ";base64," + Base64.getEncoder().encodeToString(image.bytes());
        return moderate(List.of(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))));
    }

    @Override
    public boolean isTextFlagged(String text) {
        return moderate(text);
    }

    private boolean moderate(Object input) {
        Map<String, Object> body = Map.of("model", MODEL, "input", input);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                JsonNode response = restClient.post()
                        .uri("/v1/moderations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                return flagged(response);
            } catch (RestClientResponseException e) {
                if (!isRetryable(e.getStatusCode())) {
                    throw new ReviewEngineException("Moderation 요청 실패: " + e.getStatusCode(), e);
                }
                last = e;
            } catch (ResourceAccessException e) {
                last = e;
            }
            sleep(attempt);
        }
        throw new ReviewEngineException("Moderation 재시도 후에도 실패", last);
    }

    // results 중 하나라도 flagged면 유해
    private static boolean flagged(JsonNode response) {
        JsonNode results = response == null ? null : response.get("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            throw new ReviewEngineException("Moderation 응답을 해석할 수 없음");
        }
        for (JsonNode result : results) {
            JsonNode flagged = result.get("flagged");
            if (flagged == null || !flagged.isBoolean()) {
                throw new ReviewEngineException("Moderation 응답에 flagged가 없음");
            }
            if (flagged.booleanValue()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRetryable(HttpStatusCode status) {
        return status.value() == 429 || status.is5xxServerError();
    }

    private void sleep(int attempt) {
        if (attempt == MAX_ATTEMPTS || backoff.isZero()) {
            return;
        }
        try {
            Thread.sleep(backoff.multipliedBy(attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReviewEngineException("Moderation 재시도 중 중단됨", e);
        }
    }
}
