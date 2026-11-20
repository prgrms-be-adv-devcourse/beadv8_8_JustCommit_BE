package com.justcommit.backend.payment.infrastructure.bank;

import java.math.BigDecimal;

// accountNo는 원문이다. 로그, 예외 메시지에 넣지 않는다
public record BankTransferRequest(
        String idempotencyKey,
        String bankCode,
        String accountNo,
        String accountHolder,
        BigDecimal amount
) {

    @Override
    public String toString() {
        return "BankTransferRequest[idempotencyKey=" + idempotencyKey + ", bankCode=" + bankCode
                + ", amount=" + amount + "]";
    }
}
