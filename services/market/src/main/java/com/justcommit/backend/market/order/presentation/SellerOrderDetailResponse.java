package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.OrderItemStatus;
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
        // 배송 단계별 시각 (아직 안 된 단계는 null)
        LocalDateTime bookedAt,
        LocalDateTime pickedUpAt,
        LocalDateTime inTransitAt,
        LocalDateTime deliveredAt,
        String settlementStatus,
        LocalDateTime settledAt,
        LocalDateTime createdAt,
        String recipientName,
        String recipientPhone,
        String zipcode,
        String address1,
        String address2,
        List<Item> items
) {
    public SellerOrderDetailResponse(Long sellerOrderId, Long orderId, String orderNo, BigDecimal totalAmount,
                                     BigDecimal shippingFee, OrderStatus status, LocalDateTime createdAt,
                                     String recipientName, String recipientPhone, String zipcode, String address1,
                                     String address2, List<Item> items) {
        this(sellerOrderId, orderId, orderNo, totalAmount, shippingFee, status, null, null,
                null, null, null, null, "NOT_SETTLED", null, createdAt,
                recipientName, recipientPhone, zipcode, address1, address2, items);
    }

    // 판매자 주문 상세의 상품. 필드 이름은 기존 응답(OrdersItemResult)과 같고 사진만 더한다.
    public record Item(Long id, Long productId, String productName, BigDecimal price, OrderItemStatus status,
                       String thumbnailUrl) {
        public static Item of(OrdersItemResult item, String thumbnailUrl) {
            return new Item(item.id(), item.productId(), item.productName(), item.price(), item.status(), thumbnailUrl);
        }
    }
}
