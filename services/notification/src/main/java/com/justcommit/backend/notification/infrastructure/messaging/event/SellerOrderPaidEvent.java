package com.justcommit.backend.notification.infrastructure.messaging.event;

import java.time.LocalDateTime;

public record SellerOrderPaidEvent(Long sellerOrderId, Long sellerId, Long buyerId, LocalDateTime paidAt) {
}
