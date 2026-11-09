package com.justcommit.backend.notification.infrastructure.messaging.event;

// 판매자 주문이 전부 거절된 이유: 판매자가 직접 거절 · 24시간 무응답 자동 거절
public enum SellerOrderRejectReason {
    SELLER,
    AUTO
}
