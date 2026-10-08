package com.justcommit.backend.market.cart.presentation;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(
        Long cartId,
        List<CartItemResponse> items,
        BigDecimal totalAmount, //담을당시 총액
        BigDecimal nowTotalAmount //현재 프로덕트 통해 확인한 총액
) {
}
