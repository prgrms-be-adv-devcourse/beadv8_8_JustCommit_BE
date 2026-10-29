package com.justcommit.backend.market;

import java.time.LocalDateTime;

public record SellerOrderCancelledEvent(Long orderId, Long sellerOrderId, Long sellerId, Long buyerId, LocalDateTime cancelledAt) {
}
