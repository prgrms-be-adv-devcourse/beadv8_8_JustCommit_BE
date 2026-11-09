package com.justcommit.backend.notification.infrastructure.messaging.event;

import java.time.LocalDateTime;

// 판매자가 주문을 수락함. rejectedItemCount가 1 이상이면 일부 상품은 거절(구매자에게 일부 거절 알림 1건)
public record SellerOrderAcceptedEvent(Long orderId, Long sellerOrderId, Long sellerId, Long buyerId,
                                       int rejectedItemCount, LocalDateTime acceptedAt) {
}
