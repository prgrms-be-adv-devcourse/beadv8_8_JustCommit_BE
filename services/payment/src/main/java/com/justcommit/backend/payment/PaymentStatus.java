package com.justcommit.backend.payment;

public enum PaymentStatus {
    PAID,
    ALREADY_PAID,
    INSUFFICIENT_BALANCE,
    WALLET_NOT_FOUND,

    REFUNDED,
    ALREADY_REFUNDED
}
