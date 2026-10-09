package com.justcommit.backend.payment.presentation.dto;

import com.justcommit.backend.payment.domain.ChargeStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class ChargeCancelResponse {

    private Long chargeId;
    private ChargeStatus status;
    private String pgOrderNo;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private LocalDateTime canceledAt;

}