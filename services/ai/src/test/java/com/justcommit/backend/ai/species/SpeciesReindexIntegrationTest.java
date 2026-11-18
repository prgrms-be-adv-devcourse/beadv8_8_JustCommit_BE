package com.justcommit.backend.ai.species;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.testcontainers.junit.jupiter.Testcontainers;

// 재임베딩 운영 API: 처음엔 215건을 임베딩하고, 다시 부르면 모델을 부르지 않는다 (임베딩은 스텁)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SpeciesReindexIntegrationTest {

    private static final String INTERNAL_KEY = "test-internal-key";
    private static final int DIMENSIONS = 1536;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        registry.add("jwt.public-key", SpeciesReindexIntegrationTest::newPublicKey);
        registry.add("internal-api.key", () -> INTERNAL_KEY);
        registry.add("spring.ai.openai.api-key", () -> "test-key");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    // OpenAI 대신 받은 문장 수만 세고 고정 벡터를 돌려주는 임베딩 모델
    @TestConfiguration
    static class StubEmbeddingConfig {
        @Bean
        @Primary
        CountingEmbeddingModel countingEmbeddingModel() {
            return new CountingEmbeddingModel();
        }
    }

    static class CountingEmbeddingModel implements EmbeddingModel {
        final AtomicInteger embeddedTexts = new AtomicInteger();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<String> texts = request.getInstructions();
            embeddedTexts.addAndGet(texts.size());
            float[] vector = new float[DIMENSIONS];
            vector[0] = 1f;
            List<Embedding> embeddings = new java.util.ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                embeddings.add(new Embedding(vector.clone(), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            embeddedTexts.incrementAndGet();
            float[] vector = new float[DIMENSIONS];
            vector[0] = 1f;
            return vector;
        }

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }
    }

    @LocalServerPort int port;
    @Autowired JdbcClient jdbcClient;
    @Autowired CountingEmbeddingModel embeddingModel;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clearVectors() {
        jdbcClient.sql("DELETE FROM species_vector_store").update();
        embeddingModel.embeddedTexts.set(0);
    }

    @Test
    @DisplayName("재임베딩하면 215건이 저장되고, 다시 부르면 임베딩 모델을 부르지 않는다")
    void reindexIsIdempotent() throws Exception {
        HttpResponse<String> first = reindex(INTERNAL_KEY);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("\"total\":215", "\"embedded\":215", "\"skipped\":0");
        assertThat(vectorCount()).isEqualTo(215);
        assertThat(embeddingModel.embeddedTexts.get()).isEqualTo(215);

        HttpResponse<String> second = reindex(INTERNAL_KEY);

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).contains("\"embedded\":0", "\"skipped\":215");
        assertThat(vectorCount()).isEqualTo(215);
        assertThat(embeddingModel.embeddedTexts.get()).isEqualTo(215);
    }

    @Test
    @DisplayName("문서가 바뀐 품종만 다시 임베딩한다")
    void reindexOnlyChanged() throws Exception {
        reindex(INTERNAL_KEY);
        jdbcClient.sql("UPDATE species_vector_store SET metadata = '{\"documentHash\":\"old\"}' WHERE id = 14").update();
        embeddingModel.embeddedTexts.set(0);

        HttpResponse<String> response = reindex(INTERNAL_KEY);

        assertThat(response.body()).contains("\"embedded\":1", "\"skipped\":214");
        assertThat(embeddingModel.embeddedTexts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("내부 키가 없거나 틀리면 401이고 임베딩하지 않는다")
    void rejectsWithoutInternalKey() throws Exception {
        assertThat(reindex(null).statusCode()).isEqualTo(401);
        assertThat(reindex("wrong").statusCode()).isEqualTo(401);
        assertThat(vectorCount()).isZero();
    }

    private HttpResponse<String> reindex(String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/internal/v1/ai/admin/reindex?target=SPECIES"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (key != null) {
            request.header("X-Internal-Key", key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int vectorCount() {
        return jdbcClient.sql("SELECT count(*) FROM species_vector_store").query(Integer.class).single();
    }

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
