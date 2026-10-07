package com.justcommit.backend.market;

import java.math.BigDecimal;

public record OrdersItemResult(
        Long id,
        Long productId,
        String productName,
        BigDecimal price,
        OrderItemStatus status
) {
}
