package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.OrdersItemResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record SellerOrderDetailResponse(
        Long sellerOrderId,
        Long orderId,
        String orderNo,
        BigDecimal totalAmount,
        BigDecimal shippingFee,
        OrderStatus status,
        String shipmentStatus,
        String trackingNo,
        String settlementStatus,
        LocalDateTime settledAt,
        LocalDateTime createdAt,
        String recipientName,
        String recipientPhone,
        String zipcode,
        String address1,
        String address2,
        List<OrdersItemResult> items
) {
    public SellerOrderDetailResponse(Long sellerOrderId, Long orderId, String orderNo, BigDecimal totalAmount,
                                     BigDecimal shippingFee, OrderStatus status, LocalDateTime createdAt,
                                     String recipientName, String recipientPhone, String zipcode, String address1,
                                     String address2, List<OrdersItemResult> items) {
        this(sellerOrderId, orderId, orderNo, totalAmount, shippingFee, status, null, null,
                "NOT_SETTLED", null, createdAt,
                recipientName, recipientPhone, zipcode, address1, address2, items);
    }
}
