package com.justcommit.backend.market;

import java.time.LocalDateTime;

public record OrderItemAutoConfirmedEvent(Long orderItemId, Long orderId, Long sellerOrderId, Long buyerId,
                                          LocalDateTime confirmedAt) {
}
