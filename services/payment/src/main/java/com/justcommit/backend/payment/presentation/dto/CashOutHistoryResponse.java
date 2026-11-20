package com.justcommit.backend.payment.presentation.dto;

import com.justcommit.backend.payment.domain.CashOut;
import com.justcommit.backend.payment.domain.CashOutStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 인출 내역 한 건. 계좌는 뒤 4자리만
public record CashOutHistoryResponse(
        Long cashOutId,
        BigDecimal amount,
        CashOutStatus status,
        String accountLast4,
        LocalDateTime requestedAt,
        LocalDateTime completedAt
) {

    public static CashOutHistoryResponse from(CashOut cashOut) {
        return new CashOutHistoryResponse(cashOut.getId(), cashOut.getAmount(), cashOut.getStatus(),
                cashOut.getAccountLast4(), cashOut.getRequestedAt(), cashOut.getCompletedAt());
    }
}
