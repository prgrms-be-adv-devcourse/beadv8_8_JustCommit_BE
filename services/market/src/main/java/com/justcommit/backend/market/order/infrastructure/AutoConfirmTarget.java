package com.justcommit.backend.market.order.infrastructure;

public record AutoConfirmTarget(Long orderId, Long sellerOrderId, Long orderItemId) {
}
