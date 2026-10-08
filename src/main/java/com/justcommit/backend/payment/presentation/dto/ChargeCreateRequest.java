package com.justcommit.backend.payment.presentation.dto;

import com.justcommit.backend.payment.domain.ChargeType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ChargeCreateRequest {

    private ChargeType chargeType;
    @NotNull
    @Positive
    private Long amount;
}
