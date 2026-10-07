package com.justcommit.backend.market;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record SellerOrderResult(
        Long id,
        Long sellerId,
        List<OrdersItemResult> items,
        BigDecimal totalAmount,
        LocalDateTime confirmedAt,
        BigDecimal shippingFee
) {
}
