package com.justcommit.backend.notification.infrastructure.messaging.event;

// 판매자 주문이 취소된 이유: 구매자 취소 · 판매자 거절 · 24시간 무응답 자동 거절
public enum SellerOrderCancelReason {
    BUYER,
    SELLER,
    AUTO
}
