package com.justcommit.backend.payment.out.dto;

import com.justcommit.backend.payment.domain.ChargeStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class ChargeCreateResponse {

    private Long chargeId;
    private String pgOrderNo;
    private Long amount;
    private ChargeStatus status;
    private LocalDateTime createdAt;

}