package com.justcommit.backend.market.client.product;

import java.math.BigDecimal;

public record ProductResult(
        Long productId,
        Long sellerId,
        String title,
        BigDecimal price,
        ProductStatus status,
        boolean hidden,
        String thumbnailUrl,
        String speciesName
) {
    public boolean isPurchasable() {
        return status == ProductStatus.ACTIVE && !hidden;
    }
}
