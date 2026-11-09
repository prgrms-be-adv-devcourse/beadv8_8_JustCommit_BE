package com.justcommit.backend.payment.domain;

public enum RefType {
    CHARGE,
    CHARGE_CANCEL,
    ORDER_REFUND,
    PAYMENT,
    SETTLEMENT,
    CASH_OUT,
    CASH_OUT_CANCEL,

    SETTLEMENT_FEE,
    SHIPPING_FEE,
    ORDER_ITEM_CANCEL_REFUND,
    SHIPPING_FEE_REFUND
}
