package com.justcommit.backend.market.client.payment;

public enum PaymentStatus {
    PAID,
    ALREADY_PAID,
    INSUFFICIENT_BALANCE,
    WALLET_NOT_FOUND,
    REFUNDED,
    ALREADY_REFUNDED
}
