package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrdersItemResult;
import com.justcommit.backend.market.ReturnReason;

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
        List<Item> items,
        // 수락 마감 = 결제 완료 시각 + 24시간, 수락 대기 주문상품이 없으면 null (#284). 상품별 상태는 items[].status
        LocalDateTime acceptDeadline
) {
    public SellerOrderDetailResponse(Long sellerOrderId, Long orderId, String orderNo, BigDecimal totalAmount,
                                     BigDecimal shippingFee, OrderStatus status, LocalDateTime createdAt,
                                     String recipientName, String recipientPhone, String zipcode, String address1,
                                     String address2, List<Item> items) {
        this(sellerOrderId, orderId, orderNo, totalAmount, shippingFee, status, null, null,
                null, null, null, null, "NOT_SETTLED", null, createdAt,
                recipientName, recipientPhone, zipcode, address1, address2, items, null);
    }

    // 판매자 주문 상세의 상품. 필드 이름은 기존 응답(OrdersItemResult)과 같고 사진, 반품 신청 정보(#383)를 더한다.
    // 반품 신청이 없으면 반품 필드는 모두 null. 응답 마감은 판매자 응답 전(REQUESTED)에만 값이 있다
    public record Item(Long id, Long productId, String productName, BigDecimal price, OrderItemStatus status,
                       String thumbnailUrl, ReturnReason returnReason, String returnDetail,
                       LocalDateTime returnRequestedAt, LocalDateTime returnDeadlineAt) {
        public static Item of(OrdersItemResult item, String thumbnailUrl) {
            return of(item, thumbnailUrl, null, null, null, null);
        }

        public static Item of(OrdersItemResult item, String thumbnailUrl, ReturnReason returnReason,
                              String returnDetail, LocalDateTime returnRequestedAt, LocalDateTime returnDeadlineAt) {
            return new Item(item.id(), item.productId(), item.productName(), item.price(), item.status(), thumbnailUrl,
                    returnReason, returnDetail, returnRequestedAt, returnDeadlineAt);
        }
    }
}
