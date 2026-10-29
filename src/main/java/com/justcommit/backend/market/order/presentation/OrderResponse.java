package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.market.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderResponse(
        Long orderId,
        String orderNo,
        BigDecimal totalAmount,
        Integer itemCount,
        OrderStatus status,
        String representativeItemName,
        String thumbnailUrl, // 대표 상품 사진 (없으면 null)
        LocalDateTime createdAt
) {
}
