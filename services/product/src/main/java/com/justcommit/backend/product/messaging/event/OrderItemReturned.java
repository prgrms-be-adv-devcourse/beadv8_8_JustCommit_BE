package com.justcommit.backend.product.messaging.event;

import java.util.Objects;

// market.order-item-returned.v1: 반품 환불 뒤 판매 재개 (항상 숨김)
public record OrderItemReturned(Long orderId, Long orderItemId, Long productId) {

    public OrderItemReturned {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(productId, "productId");
    }
}
