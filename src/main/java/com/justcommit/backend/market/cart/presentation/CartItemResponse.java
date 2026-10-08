package com.justcommit.backend.market.cart.presentation;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CartItemResponse(
        Long cartItemId,
        Long productId,
        String title,
        BigDecimal price,
        BigDecimal nowPrice,
        boolean isChangedPrice,
        boolean isPurchasable,
        LocalDateTime createdAt
) {
}
