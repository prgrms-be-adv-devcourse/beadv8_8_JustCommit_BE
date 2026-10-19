package com.justcommit.backend.common.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PurchaseConfirmedEvent(Long sellerOrderId, Long sellerId, BigDecimal saleAmount,
                                     LocalDateTime confirmedAt) {
}
