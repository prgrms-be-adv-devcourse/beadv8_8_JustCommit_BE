package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SellerOrderResponse(
        Long sellerOrderId,
        Long orderId,
        String orderNo,
        BigDecimal totalAmount,
        LocalDateTime confirmedAt,
        Integer itemCount,
        OrderStatus status,
        String shipmentStatus,
        String representativeItemName,
        String thumbnailUrl, // 대표 상품 사진 (없으면 null)
        LocalDateTime createdAt,
        boolean acceptPending, // 수락 대기 주문상품이 남아 있음 (#284)
        LocalDateTime acceptDeadline, // 수락 마감 = 결제 완료 시각 + 24시간, 수락 대기가 없으면 null
        boolean returnRequested, // 판매자 응답을 기다리는 반품 신청 주문상품이 있음 (#383)
        // 응답 전 반품 신청 중 응답 마감이 가장 이른 것의 신청 시각, 응답 마감(신청 시각 + 48시간). 없으면 null
        LocalDateTime returnRequestedAt,
        LocalDateTime returnDeadlineAt,
        // 반품 진행 상태 (#626). 반품 중인 주문상품 중 가장 덜 진행된 단계, 반품이 없으면 null.
        // 거절된 반품은 주문상품이 구매 확정(CONFIRMED)이라 세지 않는다
        ReturnStatus returnStatus,
        int returnItemCount // 반품 중인 주문상품 수(반품 완료 포함). 일부 반품이면 itemCount보다 작다
) {
    // 주문상품 상태 RETURN_* 4개와 1:1. 선언 순서 = 진행 순서
    public enum ReturnStatus {
        REQUESTED, IN_TRANSIT, PROCESSING, RETURNED;

        // 반품 단계가 아닌 주문상품 상태면 null
        public static ReturnStatus from(OrderItemStatus status) {
            return switch (status) {
                case RETURN_REQUESTED -> REQUESTED;
                case RETURN_IN_TRANSIT -> IN_TRANSIT;
                case RETURN_PROCESSING -> PROCESSING;
                case RETURNED -> RETURNED;
                default -> null;
            };
        }

        // 둘 중 덜 진행된 단계
        public static ReturnStatus lessProgressed(ReturnStatus a, ReturnStatus b) {
            return a.compareTo(b) <= 0 ? a : b;
        }
    }

    public SellerOrderResponse(Long sellerOrderId, Long orderId, String orderNo, BigDecimal totalAmount, LocalDateTime confirmedAt,
                               Integer itemCount, OrderStatus status, String representativeItemName,
                               LocalDateTime createdAt) {
        this(sellerOrderId, orderId, orderNo, totalAmount, confirmedAt, itemCount, status, null,
                representativeItemName, null, createdAt, false, null, false, null, null, null, 0);
    }
}
