package com.justcommit.backend.market;

import java.math.BigDecimal;
import java.util.List;

public record OrderResult (
        Long id,
        Long buyerId,
        List<SellerOrderResult> sellerOrders,
        BigDecimal totalAmount,
        String recipientName,
        String recipientPhone,
        String zipcode,
        String address1,
        String address2,
        Integer itemCount,
        OrderStatus orderStatus
) {
}
