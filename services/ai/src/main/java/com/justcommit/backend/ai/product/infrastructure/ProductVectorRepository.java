package com.justcommit.backend.ai.product.infrastructure;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// 상품 문서를 만들 때 읽는 값 (JDBC). 벡터 쓰기 · 삭제는 PgVectorStore(productVectorStore)가 한다
@Repository
@RequiredArgsConstructor
public class ProductVectorRepository {

    private final JdbcClient jdbcClient;

    // 상품 문서에 넣는 품종 요약 (species_profile). 「기타」(218)처럼 프로필이 없는 품종은 빈 값
    public record SpeciesSummary(String name, String scientificName, String difficulty, String lightNames,
            String placementNames, String growthTempName, String waterSpring, String features) {
    }

    /** 저장할 때의 문서 해시 (없으면 아직 임베딩 안 함) */
    public Optional<String> findDocumentHash(long productId) {
        return jdbcClient.sql("SELECT metadata->>'documentHash' FROM product_vector_store WHERE id = :id")
                .param("id", productId)
                .query(String.class)
                .optional();
    }

    public Optional<SpeciesSummary> findSpeciesSummary(long speciesId) {
        return jdbcClient.sql("""
                        SELECT name, scientific_name, difficulty, light_names, placement_names,
                               growth_temp_name, water_spring, features
                        FROM species_profile WHERE species_id = :speciesId
                        """)
                .param("speciesId", speciesId)
                .query(SpeciesSummary.class)
                .optional();
    }
}
