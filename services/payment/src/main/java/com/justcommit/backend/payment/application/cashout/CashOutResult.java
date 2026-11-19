package com.justcommit.backend.payment.application.cashout;

import com.justcommit.backend.payment.domain.CashOutStatus;

import java.math.BigDecimal;

// 인출 신청 결과. balanceAfter는 이 인출로 차감한 직후 잔액 (CASH_OUT 원장 행의 balance_after)
public record CashOutResult(
        Long cashOutId,
        BigDecimal amount,
        CashOutStatus status,
        String accountLast4,
        BigDecimal balanceAfter
) {
}
