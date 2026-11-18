package com.justcommit.backend.ai.recommendation.infrastructure;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// 추천 계산용 조회 (JDBC). 상품 스냅샷 · 품종 벡터만 읽고 임베딩 모델은 부르지 않는다
@Repository
@RequiredArgsConstructor
public class RecommendationQueryRepository {

    // IN ()은 SQL 오류라, 빈 목록 대신 넣는 값 (상품 · 품종 ID는 1부터)
    private static final List<Long> NONE = List.of(-1L);

    private final JdbcClient jdbcClient;

    // 벡터 후보: 유사도는 1 - 코사인 거리
    public record Candidate(int speciesId, double similarity) {
    }

    public record ActiveProduct(long productId, int speciesId) {
    }

    /** 상품 ID → 품종 ID. 판매가 끝난 상품도 포함한 모든 상태. 스냅샷에 없는 상품은 빠진다 (이벤트 반영 전) */
    public Map<Long, Integer> findSpeciesIds(Collection<Long> productIds) {
        Map<Long, Integer> speciesIds = new LinkedHashMap<>();
        if (productIds.isEmpty()) {
            return speciesIds;
        }
        jdbcClient.sql("SELECT product_id, species_id FROM product_snapshot WHERE product_id IN (:ids)")
                .param("ids", productIds)
                .query(rs -> {
                    speciesIds.put(rs.getLong("product_id"), rs.getInt("species_id"));
                });
        return speciesIds;
    }

    /**
     * 기준 품종 벡터의 평균과 가까운 품종을 고른다. 판매 중(ACTIVE · 숨김 아님) 상품이 있는 품종 안에서만 찾아,
     * 후보는 있는데 팔리는 상품이 없어 추천이 비는 일을 막는다. 기준 품종 자체 · 장바구니 상품만 있는 품종은 뺀다.
     * 기준 품종에 벡터가 하나도 없으면 평균이 NULL이라 빈 목록.
     */
    public List<Candidate> findCandidates(Collection<Integer> baseSpeciesIds, Collection<Long> excludedProductIds,
            int limit) {
        return jdbcClient.sql("""
                        WITH base AS (
                            SELECT AVG(embedding) AS v FROM species_vector_store WHERE id IN (:baseSpeciesIds)
                        )
                        SELECT s.id AS species_id, 1 - (s.embedding <=> base.v) AS similarity
                        FROM species_vector_store s, base
                        WHERE base.v IS NOT NULL
                          AND s.id NOT IN (:baseSpeciesIds)
                          AND s.id IN (
                              SELECT species_id FROM product_snapshot
                              WHERE status = 'ACTIVE' AND NOT hidden AND product_id NOT IN (:excludedProductIds)
                          )
                        ORDER BY s.embedding <=> base.v
                        LIMIT :limit
                        """)
                .param("baseSpeciesIds", baseSpeciesIds)
                .param("excludedProductIds", orNone(excludedProductIds))
                .param("limit", limit)
                .query(Candidate.class)
                .list();
    }

    /** 품종마다 판매 중 상품을 product_id 내림차순으로 최대 perSpecies개 (장바구니 상품 제외) */
    public List<ActiveProduct> findActiveProducts(Collection<Integer> speciesIds, Collection<Long> excludedProductIds,
            int perSpecies) {
        if (speciesIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                        SELECT product_id, species_id FROM (
                            SELECT product_id, species_id,
                                   ROW_NUMBER() OVER (PARTITION BY species_id ORDER BY product_id DESC) AS rn
                            FROM product_snapshot
                            WHERE species_id IN (:speciesIds) AND status = 'ACTIVE' AND NOT hidden
                              AND product_id NOT IN (:excludedProductIds)
                        ) ranked
                        WHERE rn <= :perSpecies
                        ORDER BY species_id, product_id DESC
                        """)
                .param("speciesIds", speciesIds)
                .param("excludedProductIds", orNone(excludedProductIds))
                .param("perSpecies", perSpecies)
                .query(ActiveProduct.class)
                .list();
    }

    private static Collection<Long> orNone(Collection<Long> ids) {
        return ids.isEmpty() ? NONE : ids;
    }
}
