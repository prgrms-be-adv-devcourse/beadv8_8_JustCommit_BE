package com.justcommit.backend.ai.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

// 설정한 임베딩 차원이 Flyway가 만든 species_vector_store.embedding vector(1536)와 다르면 시작할 때 실패해야 한다 (#508)
// 모듈 공유 pgvector 컨테이너에 이 클래스 전용 빈 DB를 만들어 Flyway를 돌린다 (docs/testing/strategy.md 「통합 테스트 컨테이너 규칙」)
class VectorStoreConfigTest {

    static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(SharedPostgres.createDatabase(),
                SharedPostgres.container().getUsername(), SharedPostgres.container().getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Test
    @DisplayName("설정 차원이 벡터 칸과 같으면(1536) 시작 검사를 통과한다")
    void sameDimensions() {
        PgVectorStore store = new VectorStoreConfig()
                .speciesVectorStore(jdbcTemplate, mock(EmbeddingModel.class), 1536);

        assertThatCode(store::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("설정 차원이 벡터 칸과 다르면 시작 검사에서 두 차원을 알려 주며 실패한다")
    void differentDimensions() {
        PgVectorStore store = new VectorStoreConfig()
                .speciesVectorStore(jdbcTemplate, mock(EmbeddingModel.class), 768);

        assertThatThrownBy(store::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("Actual vector dimensions is 1536, required vector dimensions is 768");
    }
}
