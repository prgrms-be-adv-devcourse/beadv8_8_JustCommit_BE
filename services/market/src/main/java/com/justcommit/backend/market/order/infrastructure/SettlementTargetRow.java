package com.justcommit.backend.market.order.infrastructure;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 정산 대상 조회 한 행 (#657). hasConfirmed: 구매 확정 주문상품이 1개 이상인지
public record SettlementTargetRow(Long sellerOrderId, Long sellerId, BigDecimal settlementBaseAmount,
                                  BigDecimal shippingFee, LocalDateTime confirmedAt, boolean hasConfirmed) {
}
