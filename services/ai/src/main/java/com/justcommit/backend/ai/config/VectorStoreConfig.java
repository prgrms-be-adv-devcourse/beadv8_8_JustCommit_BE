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

// 벡터 저장소: 품종 벡터는 species_vector_store, 상품 벡터는 product_vector_store (챗봇, #298)
// 테이블 · 인덱스는 Flyway V2 · V5가 만들므로 PgVectorStore가 스키마를 만들지 않게 한다
// 대신 시작할 때 테이블 · 칸과 embedding 칸의 차원이 설정(custom.embedding.dimensions)과 같은지 확인해, 다르면 시작을 멈춘다
@Configuration
public class VectorStoreConfig {

    public static final String SPECIES_VECTOR_TABLE = "species_vector_store";
    public static final String PRODUCT_VECTOR_TABLE = "product_vector_store";

    @Bean
    PgVectorStore speciesVectorStore(
            JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel,
            @Value("${custom.embedding.dimensions}") int dimensions) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName(SPECIES_VECTOR_TABLE)
                .idType(PgIdType.INTEGER)
                .dimensions(dimensions)
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .indexType(PgIndexType.HNSW)
                .initializeSchema(false)
                .vectorTableValidationsEnabled(true)
                .build();
    }

    // 상품 id는 BIGINT라 BIGSERIAL 형식으로 바꿔 넣는다 (스키마는 만들지 않으므로 자동 증가와는 관계없음)
    @Bean
    PgVectorStore productVectorStore(
            JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel,
            @Value("${custom.embedding.dimensions}") int dimensions) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName(PRODUCT_VECTOR_TABLE)
                .idType(PgIdType.BIGSERIAL)
                .dimensions(dimensions)
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .indexType(PgIndexType.HNSW)
                .initializeSchema(false)
                .vectorTableValidationsEnabled(true)
                .build();
    }
}
