package com.justcommit.backend.payment.domain;

public enum RefType {
    CHARGE,
    CHARGE_CANCEL,
    ORDER_REFUND,
    PAYMENT,
    SETTLEMENT,
    CASH_OUT,
    CASH_OUT_CANCEL
}
