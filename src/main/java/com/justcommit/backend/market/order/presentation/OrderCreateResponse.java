package com.justcommit.backend.market.order.presentation;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderCreateResponse (
        Long orderId,
        String orderNo,
        BigDecimal totalAmount,
        Integer itemCount,
        LocalDateTime createdAt
) {
}
