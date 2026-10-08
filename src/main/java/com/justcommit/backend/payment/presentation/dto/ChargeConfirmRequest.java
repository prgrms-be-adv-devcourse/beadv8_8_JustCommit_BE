package com.justcommit.backend.payment.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ChargeConfirmRequest {

    @NotBlank
    private String paymentKey;

    @NotBlank
    private String orderId;

    @NotNull
    @Positive
    private Long amount;
}
