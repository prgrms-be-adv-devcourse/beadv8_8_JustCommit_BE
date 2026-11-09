package com.justcommit.backend.notification.infrastructure.messaging.event;

import java.time.LocalDateTime;

// reason이 없는 이벤트(reason 추가 전 발행분)는 구매자 취소(BUYER)로 본다
public record SellerOrderCancelledEvent(Long orderId, Long sellerOrderId, Long sellerId, Long buyerId,
                                        LocalDateTime cancelledAt, SellerOrderCancelReason reason) {

    public SellerOrderCancelReason reasonOrDefault() {
        return reason == null ? SellerOrderCancelReason.BUYER : reason;
    }
}
