package com.justcommit.backend.product.messaging.event;

import java.util.Objects;

// market.order-expired.v1 · market.order-failed.v1: 결제 전 예약 해제. 두 토픽의 payload가 같아 하나로 받음
public record OrderReleased(Long orderId) {

    public OrderReleased {
        Objects.requireNonNull(orderId, "orderId");
    }
}
