package com.justcommit.backend.market.client.product;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProductResult(
        Long productId,
        Long sellerId,
        String title,
        BigDecimal price,
        ProductStatus status,
        boolean hidden,
        String thumbnailUrl,
        String speciesName,
        LocalDateTime createdAt  // 상품 등록 시각 (장바구니 추천 카드용)
) {
    public boolean isPurchasable() {
        return status == ProductStatus.ACTIVE && !hidden;
    }
}
