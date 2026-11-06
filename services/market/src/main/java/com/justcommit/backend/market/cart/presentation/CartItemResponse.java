package com.justcommit.backend.market.cart.presentation;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CartItemResponse(
        Long cartItemId,
        Long productId,
        String title,
        String thumbnailUrl,
        String speciesName,
        Long sellerId,
        String sellerName,
        BigDecimal price,
        BigDecimal nowPrice,
        boolean isChangedPrice,
        boolean isPurchasable,
        String status,
        boolean hidden,
        LocalDateTime createdAt
) {
}
