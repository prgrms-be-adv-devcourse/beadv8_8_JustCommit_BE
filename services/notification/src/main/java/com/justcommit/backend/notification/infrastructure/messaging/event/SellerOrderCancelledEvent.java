package com.justcommit.backend.notification.infrastructure.messaging.event;

import java.time.LocalDateTime;

public record SellerOrderCancelledEvent(Long orderId, Long sellerOrderId, Long sellerId, Long buyerId, LocalDateTime cancelledAt) {
}
