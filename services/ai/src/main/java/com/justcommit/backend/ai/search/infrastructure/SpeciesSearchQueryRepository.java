package com.justcommit.backend.ai.search.infrastructure;

import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// 품종 의미 검색용 조회 (JDBC). 추천 쿼리(RecommendationQueryRepository)와 같은 방식: 유사도는 1 - 코사인 거리(<=>)
@Repository
@RequiredArgsConstructor
public class SpeciesSearchQueryRepository {

    private final JdbcClient jdbcClient;

    public record SimilarSpecies(int speciesId, String name, double similarity) {
    }

    public record ActiveProduct(long productId, int speciesId) {
    }

    /**
     * 질의 벡터와 가까운 품종을 유사도 순으로 limit개. 조인 · 유사도 조건이 ORDER BY 거리 + LIMIT 앞에 있어 HNSW가 아니라
     * 전체 비교(Seq Scan)로 돈다. 품종 215개라 몇 ms이고 결과도 정확하다 (#636 EXPLAIN, perf/README.md 「HNSW 확인」).
     * 품종 이름은 프로필(species_profile)에서 읽고, 프로필이 없는 벡터는 뺀다
     */
    public List<SimilarSpecies> findSimilarSpecies(float[] queryVector, double minSimilarity, int limit) {
        return jdbcClient.sql("""
                        SELECT s.id AS species_id, p.name, 1 - (s.embedding <=> CAST(:v AS vector)) AS similarity
                        FROM species_vector_store s
                        JOIN species_profile p ON p.species_id = s.id
                        WHERE 1 - (s.embedding <=> CAST(:v AS vector)) >= :minSimilarity
                        ORDER BY s.embedding <=> CAST(:v AS vector)
                        LIMIT :limit
                        """)
                .param("v", toVectorLiteral(queryVector))
                .param("minSimilarity", minSimilarity)
                .param("limit", limit)
                .query(SimilarSpecies.class)
                .list();
    }

    /** 품종마다 판매 중(ACTIVE · 숨김 아님) 상품을 product_id 내림차순(최신)으로 최대 perSpecies개 */
    public List<ActiveProduct> findActiveProducts(Collection<Integer> speciesIds, int perSpecies) {
        if (speciesIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                        SELECT product_id, species_id FROM (
                            SELECT product_id, species_id,
                                   ROW_NUMBER() OVER (PARTITION BY species_id ORDER BY product_id DESC) AS rn
                            FROM product_snapshot
                            WHERE species_id IN (:speciesIds) AND status = 'ACTIVE' AND NOT hidden
                        ) ranked
                        WHERE rn <= :perSpecies
                        ORDER BY species_id, product_id DESC
                        """)
                .param("speciesIds", speciesIds)
                .param("perSpecies", perSpecies)
                .query(ActiveProduct.class)
                .list();
    }

    // pgvector 문자열 형식 "[0.1,0.2,...]"
    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
