package com.justcommit.backend.product.messaging.event;

import java.util.Objects;

// market.order-paid.v1: 판매 완료 (docs/reference/kafka-topics.md)
public record OrderPaid(Long orderId) {

    public OrderPaid {
        Objects.requireNonNull(orderId, "orderId");
    }
}
