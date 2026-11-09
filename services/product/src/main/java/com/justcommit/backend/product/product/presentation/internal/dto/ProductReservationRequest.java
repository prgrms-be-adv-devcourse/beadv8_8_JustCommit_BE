package com.justcommit.backend.product.product.presentation.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;

// market → product 상품 예약 요청. expectedPrice는 주문서에 보여 준 가격
public record ProductReservationRequest(
        @NotNull Long orderId,
        @NotEmpty List<@NotNull @Valid Item> items
) {

    public record Item(
            @NotNull Long productId,
            @NotNull @PositiveOrZero BigDecimal expectedPrice
    ) {
    }
}
