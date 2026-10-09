package com.justcommit.backend.payment.presentation.dto;

import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.WalletTransaction;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class WalletTransactionResponse {
    private Long transactionId;
    private RefType refType;
    private Long refId;
    private LocalDateTime refOccurredTime;
    private TransactionStatus status;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private LocalDateTime createdAt;

    public static WalletTransactionResponse from(WalletTransaction transaction) {
        return new WalletTransactionResponse(
                transaction.getId(),
                transaction.getRefType(),
                transaction.getRefId(),
                transaction.getRefOccurredTime(),
                transaction.getStatus(),
                transaction.getAmount(),
                transaction.getBalanceAfter(),
                transaction.getCreatedAt()
        );
    }
}
