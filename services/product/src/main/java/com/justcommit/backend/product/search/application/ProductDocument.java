package com.justcommit.backend.product.search.application;

import com.justcommit.backend.product.config.ClockConfig;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.species.domain.Species;
import java.util.List;

/**
 * OpenSearch 상품 문서 (문서 ID = 상품 ID). 필드는 resources/opensearch/products-v1.json 매핑과 1:1이다.
 * 매핑이 {@code dynamic: strict}라 여기에 없는 필드를 넣으면 OpenSearch가 거절한다.
 *
 * <p>상태 · 숨김도 함께 저장하고, 검색할 때 노출 규칙(ACTIVE · PAYMENT_PENDING · SOLD_OUT, 숨김 아님)으로 거른다 (#486).
 * 그래서 숨김을 풀거나 검수가 통과돼도 문서를 새로 만들 필요 없이 같은 문서를 덮어쓰면 된다.
 */
public record ProductDocument(
        Long productId,
        // 판매자 상점 목록(sellerId 조건)용 (#486)
        Long sellerId,
        String title,
        String description,
        Long speciesId,
        String speciesName,
        List<String> speciesAliases,
        String scientificName,
        String category,
        String light,
        String watering,
        String difficulty,
        long price,
        String status,
        boolean hidden,
        // ISO-8601 문자열 (예: 2026-11-19T10:00:00+09:00). OpenSearch date 필드가 그대로 읽음
        String createdAt
) {

    public static ProductDocument of(Product product, List<String> aliases) {
        Species species = product.getSpecies();
        return new ProductDocument(
                product.getId(),
                product.getSellerId(),
                product.getTitle(),
                product.getDescription(),
                species.getId(),
                species.getName(),
                aliases,
                species.getScientificName(),
                nameOf(species.getCategory()),
                nameOf(species.getLight()),
                nameOf(species.getWatering()),
                nameOf(species.getDifficulty()),
                product.getPrice().longValue(),
                product.getStatus().name(),
                product.isHidden(),
                // DB 시각은 한국 시간(LocalDateTime)이라 시간대를 붙여야 OpenSearch가 UTC로 잘못 읽지 않음
                product.getCreatedAt() == null ? null
                        : product.getCreatedAt().atZone(ClockConfig.SEOUL).toOffsetDateTime().toString());
    }

    // 「기타」 품종은 관리 속성이 비어 있음
    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
