package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrdersItemResult;
import com.justcommit.backend.market.ReturnReason;
import com.justcommit.backend.market.order.application.ReturnTimeline;

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

    // 구매자 주문 상세의 상품. 반품 필드(#606)는 판매 상세 items[]와 같은 이름, 같은 값(출처는 ReturnTimeline).
    // 반품 신청이 없으면 반품 필드는 모두 null, 단계 시각은 그 단계가 지난 뒤에만 값이 있다
    public record OrderItemDetail(
            Long id,
            Long productId,
            String productName,
            BigDecimal price,
            OrderItemStatus status,
            boolean purchaseConfirmable,
            String thumbnailUrl, // 상품 사진 (없으면 null)
            ReturnReason returnReason,
            String returnDetail,
            LocalDateTime returnRequestedAt,
            LocalDateTime returnDeadlineAt, // 판매자 응답 전(REQUESTED)에만
            LocalDateTime returnDecidedAt, // 승인, 자동 승인, 거절 시각
            String returnShipmentStatus,
            String returnTrackingNo,
            LocalDateTime returnPickedUpAt,
            LocalDateTime returnInTransitAt,
            LocalDateTime returnArrivedAt,
            LocalDateTime returnedAt
    ) {
        // returnTimeline: 반품 신청이 없으면 null
        public static OrderItemDetail of(OrdersItemResult item, boolean purchaseConfirmable, String thumbnailUrl,
                                         ReturnTimeline returnTimeline) {
            ReturnTimeline r = returnTimeline;
            return new OrderItemDetail(item.id(), item.productId(), item.productName(), item.price(), item.status(),
                    purchaseConfirmable, thumbnailUrl,
                    r == null ? null : r.reason(), r == null ? null : r.reasonDetail(),
                    r == null ? null : r.requestedAt(), r == null ? null : r.deadlineAt(),
                    r == null ? null : r.decidedAt(), r == null ? null : r.shipmentStatus(),
                    r == null ? null : r.trackingNo(), r == null ? null : r.pickedUpAt(),
                    r == null ? null : r.inTransitAt(), r == null ? null : r.arrivedAt(),
                    r == null ? null : r.returnedAt());
        }
    }
}
