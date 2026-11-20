package com.justcommit.backend.ai.chatbot.infrastructure;

import com.justcommit.backend.ai.chatbot.application.ChatSearchResult.ProductHit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 챗봇 의미 검색 (#300). 상품 문서는 질문 벡터와 코사인 거리가 가까운 순서 (RecommendationQueryRepository와 같은 방식),
 * 품종은 품종 의미 검색(SpeciesSearchQueryRepository)이 고른 품종의 문서 본문만 읽는다.
 */
@Repository
@RequiredArgsConstructor
public class ChatSearchRepository {

    private final JdbcClient jdbcClient;

    /**
     * 판매 중(ACTIVE · 숨김 아님) 상품만. 판매 완료 · 숨김 · 삭제는 product_snapshot과 조인해 거르므로,
     * 상태만 바뀐 상품도 벡터를 다시 만들지 않고 바로 빠진다. 유사도가 minSimilarity보다 낮으면 뺀다.
     */
    public List<ProductHit> findProducts(float[] queryVector, int limit, double minSimilarity) {
        return jdbcClient.sql("""
                        SELECT * FROM (
                            SELECT s.product_id, s.species_id, p.name AS species_name, s.title, s.price,
                                   s.thumbnail_url, v.content AS document,
                                   1 - (v.embedding <=> CAST(:q AS vector)) AS similarity
                            FROM product_vector_store v
                            JOIN product_snapshot s ON s.product_id = v.id
                            LEFT JOIN species_profile p ON p.species_id = s.species_id
                            WHERE s.status = 'ACTIVE' AND NOT s.hidden
                            ORDER BY v.embedding <=> CAST(:q AS vector)
                            LIMIT :limit
                        ) nearest
                        WHERE similarity >= :minSimilarity
                        ORDER BY similarity DESC
                        """)
                .param("q", toVectorLiteral(queryVector))
                .param("limit", limit)
                .param("minSimilarity", minSimilarity)
                .query(ProductHit.class)
                .list();
    }

    /**
     * 품종 문서 본문 (근거용). 어떤 품종을 고를지는 품종 의미 검색 쿼리(SpeciesSearchQueryRepository)가 정하고,
     * 여기서는 그 품종들의 문서만 읽는다. 받은 순서(유사도 순)는 호출하는 쪽이 지킨다
     */
    public Map<Integer, String> findSpeciesDocuments(Collection<Integer> speciesIds) {
        if (speciesIds.isEmpty()) {
            return Map.of();
        }
        return jdbcClient.sql("SELECT id, content FROM species_vector_store WHERE id IN (:ids)")
                .param("ids", speciesIds)
                .query((rs, rowNum) -> Map.entry(rs.getInt("id"), rs.getString("content")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // pgvector 문자열 형식 "[0.1,0.2,...]"
    private static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
