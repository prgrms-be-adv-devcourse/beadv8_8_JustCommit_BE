package com.justcommit.backend.market;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PurchaseConfirmedEvent(Long sellerOrderId, Long sellerId, BigDecimal saleAmount, LocalDateTime confirmedAt) {
}
