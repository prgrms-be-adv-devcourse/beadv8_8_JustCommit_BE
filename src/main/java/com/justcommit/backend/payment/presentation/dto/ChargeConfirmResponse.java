package com.justcommit.backend.payment.presentation.dto;

import com.justcommit.backend.payment.domain.ChargeStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class ChargeConfirmResponse {

    private Long chargeId;
    private ChargeStatus status;
    private String pgOrderNo;
    private Long amount;
    private Long balanceAfter;
    private LocalDateTime approvedAt;

}
