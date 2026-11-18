package com.justcommit.backend.ai.recommendation.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.justcommit.backend.ai.species.application.SpeciesReindexService;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 추천 평가 세트용 품종 유사도 내보내기 (#507). 평소에는 건너뛰고, 직접 켤 때만 실제 OpenAI 임베딩을 부른다.
 *
 * <p>빈 DB에 품종 215개를 실제 모델로 재임베딩하고, 평가 세트 기준 품종마다 다른 품종과의 유사도(1 - 코사인 거리)를
 * {@value #OUTPUT}에 쓴다. 품종 문서(`SpeciesDocumentFactory`)나 임베딩 모델을 바꾸면 다시 내보낸다.
 *
 * <pre>
 * RECOMMEND_EVAL_EXPORT=true OPENAI_API_KEY=... ./gradlew :services:ai:test --tests '*EvalSimilarityExportTest' --rerun
 * </pre>
 */
@SpringBootTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RECOMMEND_EVAL_EXPORT", matches = "true")
class EvalSimilarityExportTest {

    static final String OUTPUT = "src/test/resources/recommendation/eval-similarities.csv";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        registry.add("jwt.public-key", EvalSimilarityExportTest::newPublicKey);
        registry.add("internal-api.key", () -> "test-internal-key");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired SpeciesReindexService reindexService;
    @Autowired JdbcClient jdbcClient;

    @Test
    @DisplayName("실제 임베딩으로 평가 세트 기준 품종의 유사도를 내보낸다")
    void export() throws IOException {
        // 품종 수를 고정하지 않고 시드의 프로필 수와 비교 (시드가 바뀌어도 그대로 동작)
        long profileCount = jdbcClient.sql("SELECT count(*) FROM species_profile").query(Long.class).single();
        assertThat(reindexService.reindex().embedded()).isEqualTo((int) profileCount);

        TreeSet<Integer> baseIds = new TreeSet<>();
        EvalPair.load().forEach(pair -> baseIds.add(pair.baseId()));

        List<String> lines = new ArrayList<>();
        lines.add("base_id,species_id,similarity");
        for (int baseId : baseIds) {
            jdbcClient.sql("""
                            SELECT s.id AS species_id, 1 - (s.embedding <=> b.embedding) AS similarity
                            FROM species_vector_store s, species_vector_store b
                            WHERE b.id = :baseId AND s.id <> :baseId
                            ORDER BY s.id
                            """)
                    .param("baseId", baseId)
                    .query((rs, rowNum) -> String.format(Locale.ROOT, "%d,%d,%.6f",
                            baseId, rs.getInt("species_id"), rs.getDouble("similarity")))
                    .list()
                    .forEach(lines::add);
        }
        Files.write(Path.of(OUTPUT), lines);
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
