package com.justcommit.backend.notification.infrastructure.messaging.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PurchaseConfirmedEvent(Long sellerOrderId, Long sellerId, BigDecimal saleAmount,
                                     LocalDateTime confirmedAt, String itemSummary) {
}
