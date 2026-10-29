package com.justcommit.backend.payment.presentation.dto;

import com.justcommit.backend.payment.domain.ChargeType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Getter
@NoArgsConstructor
public class ChargeCreateRequest {

    private ChargeType chargeType;
    @NotNull
    @Min(value = 10000, message = "최소 충전 금액은 10,000원입니다.")
    private BigDecimal amount;
}
