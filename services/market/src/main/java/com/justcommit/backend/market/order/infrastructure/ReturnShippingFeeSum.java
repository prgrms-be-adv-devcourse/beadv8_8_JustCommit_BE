package com.justcommit.backend.market.order.infrastructure;

import java.math.BigDecimal;

// 판매자 주문별 반품 배송비 합 (#657)
public record ReturnShippingFeeSum(Long sellerOrderId, BigDecimal amount) {
}
