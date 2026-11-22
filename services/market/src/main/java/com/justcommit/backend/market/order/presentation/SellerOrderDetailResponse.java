package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrdersItemResult;
import com.justcommit.backend.market.ReturnReason;
import com.justcommit.backend.market.order.application.ReturnTimeline;

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
        // 구매 확정 시각 (#626). 확정 전 null. 정산 시각(settledAt)은 지급 실패 · 지연 때 비거나 늦어 따로 둔다
        LocalDateTime confirmedAt,
        String settlementStatus,
        LocalDateTime settledAt,
        LocalDateTime createdAt,
        // 구매자 닉네임 (#599). 회원 복제본 기준: 탈퇴면 「탈퇴한 회원」, 복제본이 없으면 「알 수 없는 회원」
        String buyerNickname,
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
                null, null, null, null, null, "NOT_SETTLED", null, createdAt, null,
                recipientName, recipientPhone, zipcode, address1, address2, items, null);
    }

    // 판매자 주문 상세의 상품. 필드 이름은 기존 응답(OrdersItemResult)과 같고 사진, 반품 정보(#383, #606)를 더한다.
    // 반품 신청이 없으면 반품 필드는 모두 null. 응답 마감은 판매자 응답 전(REQUESTED)에만, 단계 시각은 그 단계가 지난 뒤에만 값이 있다
    // (출처는 ReturnTimeline). returnDecidedAt은 승인, 자동 승인, 거절 시각
    public record Item(Long id, Long productId, String productName, BigDecimal price, OrderItemStatus status,
                       String thumbnailUrl, ReturnReason returnReason, String returnDetail,
                       LocalDateTime returnRequestedAt, LocalDateTime returnDeadlineAt,
                       LocalDateTime returnDecidedAt, String returnShipmentStatus, String returnTrackingNo,
                       LocalDateTime returnPickedUpAt, LocalDateTime returnInTransitAt,
                       LocalDateTime returnArrivedAt, LocalDateTime returnedAt) {

        // returnTimeline: 반품 신청이 없으면 null
        public static Item of(OrdersItemResult item, String thumbnailUrl, ReturnTimeline returnTimeline) {
            ReturnTimeline r = returnTimeline;
            return new Item(item.id(), item.productId(), item.productName(), item.price(), item.status(), thumbnailUrl,
                    r == null ? null : r.reason(), r == null ? null : r.reasonDetail(),
                    r == null ? null : r.requestedAt(), r == null ? null : r.deadlineAt(),
                    r == null ? null : r.decidedAt(), r == null ? null : r.shipmentStatus(),
                    r == null ? null : r.trackingNo(), r == null ? null : r.pickedUpAt(),
                    r == null ? null : r.inTransitAt(), r == null ? null : r.arrivedAt(),
                    r == null ? null : r.returnedAt());
        }
    }
}
