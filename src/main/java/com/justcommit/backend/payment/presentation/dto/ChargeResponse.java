package com.justcommit.backend.payment.presentation.dto;

import com.justcommit.backend.payment.domain.ChargeStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class ChargeResponse {
    private Long chargeId;
    private String pgOrderNo;
    private Long amount;
    private ChargeStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime approvedAt;
    private LocalDateTime canceledAt;
}
