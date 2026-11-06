package com.justcommit.backend.market;

import java.time.LocalDateTime;

public record OrderItemCancelledEvent(Long orderItemId, Long orderId, Long sellerOrderId, Long sellerId,
                                      String productName, LocalDateTime cancelledAt) {
}
