package com.justcommit.backend.payment.presentation.dto;

import com.justcommit.backend.payment.application.cashout.CashOutResult;
import com.justcommit.backend.payment.domain.CashOutStatus;

import java.math.BigDecimal;

// 인출 신청 응답. 계좌는 뒤 4자리만
public record CashOutResponse(
        Long cashOutId,
        BigDecimal amount,
        CashOutStatus status,
        String accountLast4,
        BigDecimal balanceAfter
) {

    public static CashOutResponse from(CashOutResult result) {
        return new CashOutResponse(result.cashOutId(), result.amount(), result.status(), result.accountLast4(),
                result.balanceAfter());
    }
}
