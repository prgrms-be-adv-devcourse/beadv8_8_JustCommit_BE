package com.justcommit.backend.ai.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIdType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

// 벡터 저장소: 품종 벡터는 species_vector_store (상품 벡터를 만들면 테이블을 따로 둔다)
// 테이블 · 인덱스는 Flyway V2가 만들므로 PgVectorStore가 스키마를 만들지 않게 한다
@Configuration
public class VectorStoreConfig {

    public static final String SPECIES_VECTOR_TABLE = "species_vector_store";

    @Bean
    PgVectorStore speciesVectorStore(
            JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel,
            @Value("${spring.ai.openai.embedding.dimensions}") int dimensions) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName(SPECIES_VECTOR_TABLE)
                .idType(PgIdType.INTEGER)
                .dimensions(dimensions)
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .indexType(PgIndexType.HNSW)
                .initializeSchema(false)
                .build();
    }
}
