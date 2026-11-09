package com.justcommit.backend.notification.infrastructure.messaging.event;

import java.time.LocalDateTime;

public record OrderItemAutoConfirmedEvent(Long orderItemId, Long orderId, Long sellerOrderId, Long buyerId,
                                          LocalDateTime confirmedAt) {
}
