package com.justcommit.backend.market.cart.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

// expectedPrice는 구매자가 화면에서 확인하고 동의한 가격
public record CartItemPriceUpdateRequest(@NotEmpty List<@Valid @NotNull Item> items) {

    public record Item(@NotNull @Positive Long cartItemId, @NotNull @PositiveOrZero BigDecimal expectedPrice) {
    }
}
