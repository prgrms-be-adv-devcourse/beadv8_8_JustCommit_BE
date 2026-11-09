package com.justcommit.backend.notification.infrastructure.messaging.event;

import java.time.LocalDateTime;

// 판매자 주문의 상품이 모두 거절됨 (일부만 거절이면 SellerOrderAccepted의 rejectedItemCount)
public record SellerOrderRejectedEvent(Long orderId, Long sellerOrderId, Long sellerId, Long buyerId,
                                       SellerOrderRejectReason reason, LocalDateTime rejectedAt) {
}
