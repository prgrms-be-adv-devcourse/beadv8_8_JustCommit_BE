package com.justcommit.backend.payment.presentation.dto;

import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class WalletTransactionResponse {
    private Long transactionId;
    private RefType refType;
    private Long refId;
    private LocalDateTime refOccurredTime;
    private TransactionStatus status;
    private Long amount;
    private Long balanceAfter;
    private LocalDateTime createdAt;
}
