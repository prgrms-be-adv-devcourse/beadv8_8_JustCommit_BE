package com.justcommit.backend.ai.product;

import static com.justcommit.backend.ai.support.ProductEvents.v3;
import static org.assertj.core.api.Assertions.assertThat;

import com.justcommit.backend.ai.product.application.ProductReindexService;
import com.justcommit.backend.ai.product.infrastructure.messaging.ProductEventListener;
import com.justcommit.backend.ai.support.KeywordEmbeddingModel;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.testcontainers.junit.jupiter.Testcontainers;

// 상품 문서 임베딩 (#298): 이벤트 처리 뒤 벡터가 스냅샷과 맞고, 상태만 바뀌면 임베딩 모델을 부르지 않는다
@SpringBootTest
@Testcontainers
class ProductDocumentIntegrationTest {

    private static final long SPECIES_ID = 14L;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        registry.add("jwt.public-key", ProductDocumentIntegrationTest::newPublicKey);
        registry.add("spring.ai.openai.api-key", () -> "test-key");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @TestConfiguration
    static class StubEmbeddingConfig {
        @Bean
        @Primary
        KeywordEmbeddingModel keywordEmbeddingModel() {
            return new KeywordEmbeddingModel();
        }
    }

    @Autowired ProductEventListener listener;
    @Autowired ProductReindexService reindexService;
    @Autowired KeywordEmbeddingModel embeddingModel;
    @Autowired JdbcClient jdbcClient;

    @BeforeEach
    void clear() {
        jdbcClient.sql("DELETE FROM product_vector_store").update();
        jdbcClient.sql("DELETE FROM product_snapshot").update();
        embeddingModel.embeddedTexts.set(0);
        embeddingModel.failing.set(false);
    }

    @Test
    @DisplayName("등록 이벤트면 문서를 임베딩하고, 품종 특징 · 설명 · 크기가 문서에 들어간다")
    void embedsRegisteredProduct() {
        listener.on(v3("ProductRegistered", 1L, SPECIES_ID, "ACTIVE", false, 0, "작은 화분", "거실에서 키웠어요"));

        assertThat(vectorCount()).isEqualTo(1);
        assertThat(embeddingModel.embeddedTexts.get()).isEqualTo(1);
        String content = jdbcClient.sql("SELECT content FROM product_vector_store WHERE id = 1")
                .query(String.class).single();
        assertThat(content).contains("상품: 작은 화분", "설명: 거실에서 키웠어요", "크기: 높이 30cm, 화분 소형", "품종: ");
    }

    @Test
    @DisplayName("상태 · 숨김만 바뀐 이벤트는 임베딩 모델을 부르지 않고, 같은 이벤트가 다시 와도 그대로다")
    void statusOnlyChangeDoesNotEmbed() {
        listener.on(v3("ProductRegistered", 2L, SPECIES_ID, "ACTIVE", false, 0, "고무나무", "튼튼해요"));
        listener.on(v3("ProductStatusChanged", 2L, SPECIES_ID, "SOLD_OUT", false, 1, "고무나무", "튼튼해요"));
        listener.on(v3("ProductStatusChanged", 2L, SPECIES_ID, "ACTIVE", true, 2, "고무나무", "튼튼해요"));
        listener.on(v3("ProductStatusChanged", 2L, SPECIES_ID, "ACTIVE", true, 2, "고무나무", "튼튼해요"));

        assertThat(embeddingModel.embeddedTexts.get()).isEqualTo(1);
        assertThat(vectorCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("글이 바뀌면 다시 임베딩하고, 늦게 온 옛 이벤트는 최신 문서를 덮지 않는다")
    void reembedsOnlyLatestText() {
        listener.on(v3("ProductRegistered", 3L, SPECIES_ID, "ACTIVE", false, 0, "옛 제목", "설명"));
        listener.on(v3("ProductUpdated", 3L, SPECIES_ID, "ACTIVE", false, 2, "새 제목", "설명"));
        listener.on(v3("ProductUpdated", 3L, SPECIES_ID, "ACTIVE", false, 1, "중간 제목", "설명"));

        assertThat(embeddingModel.embeddedTexts.get()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT content FROM product_vector_store WHERE id = 3").query(String.class).single())
                .contains("상품: 새 제목");
    }

    @Test
    @DisplayName("삭제 이벤트면 벡터를 지우고, payload v2(제목 없음)는 임베딩하지 않는다")
    void deletesAndSkipsV2() {
        listener.on(v3("ProductRegistered", 4L, SPECIES_ID, "ACTIVE", false, 0, "삭제될 상품", "설명"));
        listener.on(v3("ProductStatusChanged", 4L, SPECIES_ID, "DELETED", false, 1, "삭제될 상품", "설명"));
        assertThat(vectorCount()).isZero();

        jdbcClient.sql("""
                INSERT INTO product_snapshot (product_id, species_id, seller_id, status, hidden, product_version)
                VALUES (5, 14, 7, 'ACTIVE', false, 0)
                """).update();
        assertThat(reindexService.reindex().total()).isZero();
        assertThat(vectorCount()).isZero();
    }

    @Test
    @DisplayName("임베딩이 실패해도 스냅샷은 남고, 재시도(같은 이벤트)나 재임베딩 운영 API로 채워진다")
    void embeddingFailureIsRecoverable() {
        embeddingModel.failing.set(true);
        try {
            listener.on(v3("ProductRegistered", 6L, SPECIES_ID, "ACTIVE", false, 0, "실패 상품", "설명"));
        } catch (RuntimeException expected) {
            // 리스너 예외 → Kafka 공통 설정이 재시도 후 DLT
        }
        assertThat(jdbcClient.sql("SELECT count(*) FROM product_snapshot WHERE product_id = 6")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(vectorCount()).isZero();

        embeddingModel.failing.set(false);
        ProductReindexService.ProductReindexResult result = reindexService.reindex();
        assertThat(result.embedded()).isEqualTo(1);
        assertThat(vectorCount()).isEqualTo(1);
        assertThat(reindexService.reindex().embedded()).isZero();
    }

    private int vectorCount() {
        return jdbcClient.sql("SELECT count(*) FROM product_vector_store").query(Integer.class).single();
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
