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
    SHIPPING_FEE_REFUND,

    // REVENUE 판매자 부담금 입금 (ref_id = settlementId)
    SELLER_BURDEN,
    // 마이너스 정산을 판매자 지갑에서 회수한 출금 (ref_id = settlementId)
    SETTLEMENT_COLLECTION
}
