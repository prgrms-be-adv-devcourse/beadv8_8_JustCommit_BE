package com.justcommit.backend.settlement.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PurchaseConfirmedEvent(
        Long sellerOrderId,
        Long sellerId,
        BigDecimal saleAmount,
        LocalDateTime confirmedAt,
        String itemSummary
) {
}
