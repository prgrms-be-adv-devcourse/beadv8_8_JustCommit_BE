package com.justcommit.backend.market.cart.presentation;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(
        Long cartId,
        List<CartItemResponse> items,
        BigDecimal totalAmount
) {
}
