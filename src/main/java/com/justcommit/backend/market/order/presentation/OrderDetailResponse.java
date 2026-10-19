package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.OrderItemStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailResponse(
        Long orderId,
        String orderNo,
        BigDecimal totalAmount,
        Integer itemCount,
        OrderStatus status,
        LocalDateTime createdAt,
        String recipientName,
        String recipientPhone,
        String zipcode,
        String address1,
        String address2,
        List<SellerOrderDetail> sellerOrders
) {
    public record SellerOrderDetail(
            Long id,
            Long sellerId,
            List<OrderItemDetail> items,
            BigDecimal totalAmount,
            LocalDateTime confirmedAt,
            BigDecimal shippingFee,
            Long shipmentId,
            String shipmentStatus,
            // 배송 단계별 시각 (아직 안 된 단계는 null)
            LocalDateTime bookedAt,
            LocalDateTime pickedUpAt,
            LocalDateTime inTransitAt,
            LocalDateTime deliveredAt
    ) {
    }

    public record OrderItemDetail(
            Long id,
            Long productId,
            String productName,
            BigDecimal price,
            OrderItemStatus status,
            boolean purchaseConfirmable
    ) {
    }
}
