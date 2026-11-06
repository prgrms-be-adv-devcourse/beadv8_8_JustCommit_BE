package com.justcommit.backend.market.order.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record CartOrderCreateRequest(
        @NotEmpty List<@NotNull @Positive Long> cartItemIds,
        @NotBlank String recipientName,
        @NotBlank String recipientPhone,
        @NotBlank String zipcode,
        @NotBlank String address1,
        String address2
) {
}
