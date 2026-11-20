package com.justcommit.backend.notification.infrastructure.messaging.event;

import java.time.LocalDateTime;

// 구매자가 판매자 주문의 상품 일부만 취소함 (market.events.v1). 전부 취소면 이것 대신 SellerOrderCancelledEvent
public record OrderItemCancelledEvent(Long orderItemId, Long orderId, Long sellerOrderId, Long sellerId,
                                      String productName, LocalDateTime cancelledAt) {
}
