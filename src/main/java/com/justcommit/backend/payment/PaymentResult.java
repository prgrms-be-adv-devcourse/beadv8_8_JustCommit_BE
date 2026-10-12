package com.justcommit.backend.payment;

import java.math.BigDecimal;

public record PaymentResult(PaymentStatus status, BigDecimal balanceAfter) {

    public boolean isPaid() {
        return status == PaymentStatus.PAID || status == PaymentStatus.ALREADY_PAID;
    }

    public static PaymentResult paid(BigDecimal balanceAfter) {
        return new PaymentResult(PaymentStatus.PAID, balanceAfter);
    }

    public static PaymentResult alreadyPaid(BigDecimal balance) {
        return new PaymentResult(PaymentStatus.ALREADY_PAID, balance);
    }

    public static PaymentResult insufficientBalance(BigDecimal balance) {
        return new PaymentResult(PaymentStatus.INSUFFICIENT_BALANCE, balance);
    }

    public static PaymentResult walletNotFound() {
        return new PaymentResult(PaymentStatus.WALLET_NOT_FOUND, null);
    }
}