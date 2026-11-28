package com.justcommit.backend.ai.search.infrastructure;

import com.justcommit.backend.ai.search.application.QueryEmbedder;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.justcommit.backend.ai.search.application.QueryEmbedder.QueryEmbeddingException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

// 검색용 OpenAI 임베딩: WireMock으로 응답 · 지연 · 오류를 흉내 내 타임아웃과 재시도 없음을 확인한다
class OpenAiQueryEmbedderTest {

    private static final String SCOPE = QueryEmbedder.SPECIES_SEARCH_SCOPE;

    @RegisterExtension
    static final WireMockExtension OPENAI = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private OpenAiQueryEmbedder embedder(Duration timeout) {
        return new OpenAiQueryEmbedder("test-key", OPENAI.baseUrl() + "/v1", "text-embedding-3-small", 1536, timeout,
                newCache());
    }

    private static QueryEmbeddingCache newCache() {
        return new QueryEmbeddingCache(100, Duration.ofHours(24), new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("같은 질문(공백만 다름)을 다시 보내면 OpenAI를 부르지 않고 같은 벡터를 돌려준다 (#636)")
    void secondSameQueryUsesCache() {
        OPENAI.stubFor(post(urlPathEqualTo("/v1/embeddings")).willReturn(okJson(embeddingResponse())));
        OpenAiQueryEmbedder embedder = embedder(Duration.ofSeconds(3));

        float[] first = embedder.embed("  햇빛   적은 방 ", SCOPE);
        float[] second = embedder.embed("햇빛 적은 방", SCOPE);

        assertThat(second).containsExactly(first);
        OPENAI.verify(1, postRequestedFor(urlPathEqualTo("/v1/embeddings"))
                .withRequestBody(matchingJsonPath("$.input[0]", equalTo("햇빛 적은 방"))));
    }

    @Test
    @DisplayName("실패한 질문은 저장하지 않아 다음 요청에서 OpenAI를 다시 부른다 (#636)")
    void failureIsNotCached() {
        OPENAI.stubFor(post(urlPathEqualTo("/v1/embeddings")).willReturn(aResponse().withStatus(500)));
        OpenAiQueryEmbedder embedder = embedder(Duration.ofSeconds(3));
        assertThatThrownBy(() -> embedder.embed("햇빛 적은 방", SCOPE)).isInstanceOf(QueryEmbeddingException.class);

        OPENAI.stubFor(post(urlPathEqualTo("/v1/embeddings")).willReturn(okJson(embeddingResponse())));
        assertThat(embedder.embed("햇빛 적은 방", SCOPE)).hasSize(1536);
        OPENAI.verify(2, postRequestedFor(urlPathEqualTo("/v1/embeddings")));
    }

    @Test
    @DisplayName("text-embedding-3-small · 1536차원으로 요청하고 벡터를 돌려준다")
    void embeds() {
        OPENAI.stubFor(post(urlPathEqualTo("/v1/embeddings")).willReturn(okJson(embeddingResponse())));

        float[] vector = embedder(Duration.ofSeconds(3)).embed("햇빛 적은 방", SCOPE);

        assertThat(vector).hasSize(1536);
        OPENAI.verify(postRequestedFor(urlPathEqualTo("/v1/embeddings"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("text-embedding-3-small")))
                .withRequestBody(matchingJsonPath("$.input[0]", equalTo("햇빛 적은 방"))));
    }

    @Test
    @DisplayName("응답이 제한 시간을 넘기면 다시 보내지 않고 바로 실패한다")
    void timesOutWithoutRetry() {
        OPENAI.stubFor(post(urlPathEqualTo("/v1/embeddings"))
                .willReturn(okJson(embeddingResponse()).withFixedDelay(2_000)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> embedder(Duration.ofMillis(500)).embed("햇빛 적은 방", SCOPE))
                .isInstanceOf(QueryEmbeddingException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_800));
        OPENAI.verify(1, postRequestedFor(urlPathEqualTo("/v1/embeddings")));
    }

    @Test
    @DisplayName("OpenAI가 5xx · 429로 답해도 다시 보내지 않고 실패한다")
    void failsWithoutRetryOnServerError() {
        OPENAI.stubFor(post(urlPathEqualTo("/v1/embeddings")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> embedder(Duration.ofSeconds(3)).embed("햇빛 적은 방", SCOPE))
                .isInstanceOf(QueryEmbeddingException.class);
        OPENAI.verify(1, postRequestedFor(urlPathEqualTo("/v1/embeddings")));

        OPENAI.resetRequests();
        OPENAI.stubFor(post(urlPathEqualTo("/v1/embeddings")).willReturn(aResponse().withStatus(429)));
        assertThatThrownBy(() -> embedder(Duration.ofSeconds(3)).embed("햇빛 적은 방", SCOPE))
                .isInstanceOf(QueryEmbeddingException.class);
        OPENAI.verify(1, postRequestedFor(urlPathEqualTo("/v1/embeddings")));
    }

    @Test
    @DisplayName("키가 없어도 만들어지고(앱 시작은 그대로) 검색할 때만 실패한다")
    void failsOnlyOnSearchWithoutApiKey() {
        OpenAiQueryEmbedder noKey =
                new OpenAiQueryEmbedder("", OPENAI.baseUrl() + "/v1", "text-embedding-3-small", 1536, Duration.ofSeconds(3),
                        newCache());

        assertThatThrownBy(() -> noKey.embed("햇빛 적은 방", SCOPE)).isInstanceOf(QueryEmbeddingException.class);
        OPENAI.verify(0, postRequestedFor(urlPathEqualTo("/v1/embeddings")));
    }

    private static String embeddingResponse() {
        String embedding = IntStream.range(0, 1536).mapToObj(i -> "0.001").collect(Collectors.joining(",", "[", "]"));
        return """
                {"object":"list","model":"text-embedding-3-small",
                 "data":[{"object":"embedding","index":0,"embedding":%s}],
                 "usage":{"prompt_tokens":3,"total_tokens":3}}
                """.formatted(embedding);
    }
}
