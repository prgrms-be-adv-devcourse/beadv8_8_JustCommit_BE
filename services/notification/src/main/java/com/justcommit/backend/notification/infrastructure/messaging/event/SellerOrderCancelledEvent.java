package com.justcommit.backend.notification.infrastructure.messaging.event;

import java.time.LocalDateTime;

// 구매자가 판매자 주문을 취소함 (판매자 거절 · 자동 거절은 SellerOrderRejectedEvent)
public record SellerOrderCancelledEvent(Long orderId, Long sellerOrderId, Long sellerId, Long buyerId, LocalDateTime cancelledAt) {
}
