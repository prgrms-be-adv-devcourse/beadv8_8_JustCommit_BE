package com.justcommit.backend.market.cart.presentation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CartItemAddRequest(@NotNull @Positive Long productId) {
}
