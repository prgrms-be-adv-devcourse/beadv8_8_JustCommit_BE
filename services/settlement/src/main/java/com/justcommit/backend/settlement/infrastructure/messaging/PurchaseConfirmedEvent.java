package com.justcommit.backend.settlement.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// market의 PurchaseConfirmed payload (docs/reference/kafka-topics.md 「payload」).
// shippingFee: 배송비. 정산 지급 때 REVENUE로 옮긴다
// sellerBurdenAmount: 판매자 부담금 (#433). 칸이 없으면(market이 보내기 전) 0
public record PurchaseConfirmedEvent(
        Long sellerOrderId,
        Long sellerId,
        BigDecimal saleAmount,
        BigDecimal shippingFee,
        BigDecimal sellerBurdenAmount,
        LocalDateTime confirmedAt,
        String itemSummary
) {

    // 업무 키 · 금액이 없으면 다시 받아도 같으므로 형식 오류로 봄 (Jackson 예외로 감싸져 재시도 없이 DLT)
    public PurchaseConfirmedEvent {
        if (sellerOrderId == null || sellerId == null || saleAmount == null) {
            throw new IllegalArgumentException("sellerOrderId · sellerId · saleAmount가 없는 구매 확정 이벤트");
        }
        if (sellerBurdenAmount == null) {
            sellerBurdenAmount = BigDecimal.ZERO;
        }
    }
}
