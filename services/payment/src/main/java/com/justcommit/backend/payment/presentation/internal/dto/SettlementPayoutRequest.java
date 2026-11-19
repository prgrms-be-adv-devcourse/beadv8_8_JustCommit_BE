package com.justcommit.backend.payment.presentation.internal.dto;

import java.math.BigDecimal;

// payoutAmount = 판매액 - fee - sellerBurdenAmount (0원 · 음수 가능).
// shippingFee · sellerBurdenAmount가 없으면 0 (settlement #433 배포 전 요청 호환)
public record SettlementPayoutRequest(
        Long settlementId,
        Long sellerId,
        Long sellerOrderId,
        BigDecimal payoutAmount,
        BigDecimal fee,
        BigDecimal shippingFee,
        BigDecimal sellerBurdenAmount
) {

    public SettlementPayoutRequest {
        shippingFee = shippingFee == null ? BigDecimal.ZERO : shippingFee;
        sellerBurdenAmount = sellerBurdenAmount == null ? BigDecimal.ZERO : sellerBurdenAmount;
    }
}
