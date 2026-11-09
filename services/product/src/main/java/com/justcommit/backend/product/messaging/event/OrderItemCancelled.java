package com.justcommit.backend.product.messaging.event;

import java.util.Objects;

// market.order-item-cancelled.v1: 환불 성공 뒤 판매 재개. 주문 전체 취소도 주문상품마다 옴
public record OrderItemCancelled(Long orderId, Long orderItemId, Long productId, CancelReason reason) {

    public OrderItemCancelled {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(reason, "reason");
    }

    public enum CancelReason {
        BUYER,  // 구매자 취소 → 숨기지 않음
        SELLER, // 판매자 거절 → 숨김
        AUTO    // 24시간 자동 거절 → 숨김
    }

    public boolean hidesProduct() {
        return reason != CancelReason.BUYER;
    }
}
