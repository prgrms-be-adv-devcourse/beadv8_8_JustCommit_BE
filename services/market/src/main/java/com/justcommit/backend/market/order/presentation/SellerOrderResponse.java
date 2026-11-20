package com.justcommit.backend.market.order.presentation;

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
        LocalDateTime returnDeadlineAt
) {
    public SellerOrderResponse(Long sellerOrderId, Long orderId, String orderNo, BigDecimal totalAmount, LocalDateTime confirmedAt,
                               Integer itemCount, OrderStatus status, String representativeItemName,
                               LocalDateTime createdAt) {
        this(sellerOrderId, orderId, orderNo, totalAmount, confirmedAt, itemCount, status, null,
                representativeItemName, null, createdAt, false, null, false, null, null);
    }
}
