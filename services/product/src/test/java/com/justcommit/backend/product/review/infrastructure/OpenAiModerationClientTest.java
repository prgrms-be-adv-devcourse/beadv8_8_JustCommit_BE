package com.justcommit.backend.product.review.infrastructure;

import com.justcommit.backend.product.image.application.ImageData;
import com.justcommit.backend.product.review.application.ReviewEngineException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiModerationClientTest {

    private static final String URL = "https://api.openai.test/v1/moderations";

    private MockRestServiceServer server;
    private OpenAiModerationClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiModerationClient(builder, "https://api.openai.test", "test-key", Duration.ZERO);
    }

    private static String result(boolean... flagged) {
        StringBuilder results = new StringBuilder();
        for (boolean f : flagged) {
            if (!results.isEmpty()) results.append(',');
            results.append("{\"flagged\":").append(f).append(",\"categories\":{}}");
        }
        return "{\"id\":\"modr-1\",\"model\":\"omni-moderation-latest\",\"results\":[" + results + "]}";
    }

    @Test
    @DisplayName("사진은 data URL로 한 장씩 보내고 flagged를 돌려줌")
    void image() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("omni-moderation-latest"))
                .andExpect(jsonPath("$.input[0].type").value("image_url"))
                .andExpect(jsonPath("$.input[0].image_url.url").value("data:image/png;base64,AQI="))
                .andRespond(withSuccess(result(true), MediaType.APPLICATION_JSON));

        assertThat(client.isImageFlagged(new ImageData(new byte[]{1, 2}, "image/png"))).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("글은 문자열 그대로 보내고, 결과 중 하나라도 flagged면 유해")
    void text() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.input").value("몬스테라 팝니다"))
                .andRespond(withSuccess(result(false, true), MediaType.APPLICATION_JSON));

        assertThat(client.isTextFlagged("몬스테라 팝니다")).isTrue();
    }

    @Test
    @DisplayName("5xx · 429는 다시 시도해서 성공하면 그 결과")
    void retry() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withSuccess(result(false), MediaType.APPLICATION_JSON));

        assertThat(client.isTextFlagged("글")).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("3번 모두 실패하면 ReviewEngineException")
    void retryExhausted() {
        server.expect(ExpectedCount.times(3), requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.isTextFlagged("글")).isInstanceOf(ReviewEngineException.class);
        server.verify();
    }

    @Test
    @DisplayName("400은 다시 시도하지 않음")
    void badRequest() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withBadRequest());

        assertThatThrownBy(() -> client.isTextFlagged("글")).isInstanceOf(ReviewEngineException.class);
        server.verify();
    }

    @Test
    @DisplayName("응답을 해석할 수 없으면 ReviewEngineException")
    void malformed() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.isTextFlagged("글")).isInstanceOf(ReviewEngineException.class);
    }

    @Test
    @DisplayName("키가 있어야 설정된 것으로 봄 (없으면 엔진 오류를 재처리하지 않음)")
    void configured() {
        assertThat(client.isConfigured()).isTrue();
        assertThat(new OpenAiModerationClient(RestClient.builder(), "https://api.openai.test", "", Duration.ZERO).isConfigured())
                .isFalse();
    }

    @Test
    @DisplayName("스프링이 쓰는 생성자는 타임아웃을 건 HTTP 클라이언트로 만듦")
    void timeoutConstructor() {
        OpenAiModerationClient withTimeouts = new OpenAiModerationClient(RestClient.builder(), "https://api.openai.test",
                "key", Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(20));
        assertThat(withTimeouts.isConfigured()).isTrue();
    }
}
