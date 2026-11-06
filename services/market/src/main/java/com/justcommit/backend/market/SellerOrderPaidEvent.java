package com.justcommit.backend.market;

import java.time.LocalDateTime;

public record SellerOrderPaidEvent(Long sellerOrderId, Long sellerId, Long buyerId, LocalDateTime paidAt) {
}
