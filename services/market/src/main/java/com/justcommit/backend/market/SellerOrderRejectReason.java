package com.justcommit.backend.market;

// 판매자 주문이 전부 거절된 이유: 판매자 직접 거절, 24시간 자동 거절 (SellerOrderRejectedEvent.reason)
public enum SellerOrderRejectReason {
    SELLER,
    AUTO
}
