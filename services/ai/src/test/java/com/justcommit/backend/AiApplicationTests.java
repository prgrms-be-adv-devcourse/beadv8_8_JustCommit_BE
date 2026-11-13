package com.justcommit.backend;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class AiApplicationTests {

    // 로컬 compose와 같은 pgvector 이미지. postgres와 호환된다고 알려야 PostgreSQLContainer가 받아 준다
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    // OpenAI 대신 응답하는 스텁 서버 (실제 API 키 · 비용 없이 임베딩 호출 확인)
    @RegisterExtension
    static final WireMockExtension OPENAI = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key", AiApplicationTests::newPublicKey);
        registry.add("spring.ai.openai.base-url", () -> OPENAI.baseUrl() + "/v1");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EmbeddingModel embeddingModel;

    @Test
    @DisplayName("빈 DB에서 Flyway V1이 vector 확장을 만들고 ai 서비스가 뜬다")
    void contextLoads() {
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().getVersion(), MigrationInfo::getState)
                .contains(tuple("1", MigrationState.SUCCESS));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("임베딩 모델은 text-embedding-3-small · 1536차원으로 요청하고 벡터를 돌려준다 (스텁)")
    void embed() {
        OPENAI.stubFor(post(urlPathEqualTo("/v1/embeddings"))
                .willReturn(okJson(embeddingResponse(1536))));

        float[] vector = embeddingModel.embed("몬스테라");

        assertThat(vector).hasSize(1536);
        OPENAI.verify(postRequestedFor(urlPathEqualTo("/v1/embeddings"))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("text-embedding-3-small")))
                .withRequestBody(matchingJsonPath("$.dimensions", equalTo("1536"))));
    }

    // OpenAI 임베딩 응답 형식: data[0].embedding에 숫자 배열
    private static String embeddingResponse(int dimensions) {
        String embedding = IntStream.range(0, dimensions)
                .mapToObj(i -> "0.001")
                .collect(Collectors.joining(",", "[", "]"));
        return """
                {"object":"list","model":"text-embedding-3-small",
                 "data":[{"object":"embedding","index":0,"embedding":%s}],
                 "usage":{"prompt_tokens":3,"total_tokens":3}}
                """.formatted(embedding);
    }

    // 테스트할 때마다 새 RSA 키를 만들어 공개키만 넣는다 (키를 파일에 남기지 않음)
    private static String newPublicKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
