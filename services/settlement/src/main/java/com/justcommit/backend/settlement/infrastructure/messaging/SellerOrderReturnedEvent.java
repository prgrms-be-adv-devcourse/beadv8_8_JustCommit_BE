package com.justcommit.backend.settlement.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// market의 SellerOrderReturned payload: 전부 반품 (docs/reference/kafka-topics.md 「payload」, #432).
// sellerBurdenAmount: 판매자 부담금 = 반품 완료된 반품 배송비 합 + 원래 배송비. 배송비가 0원이면 0일 수 있다
// returnedAt: 판매자 주문 종료 시각. 받기만 하고 정산에 쓰지 않는다
public record SellerOrderReturnedEvent(
        Long sellerOrderId,
        Long sellerId,
        BigDecimal sellerBurdenAmount,
        LocalDateTime returnedAt,
        String itemSummary
) {

    // 업무 키나 부담금이 없거나 부담금이 음수면 다시 받아도 같으므로 형식 오류로 봄 (Jackson 예외로 감싸져 재시도 없이 DLT)
    public SellerOrderReturnedEvent {
        if (sellerOrderId == null || sellerId == null || sellerBurdenAmount == null) {
            throw new IllegalArgumentException("sellerOrderId, sellerId, sellerBurdenAmount가 없는 전부 반품 이벤트");
        }
        if (sellerBurdenAmount.signum() < 0) {
            throw new IllegalArgumentException("sellerBurdenAmount가 음수인 전부 반품 이벤트: " + sellerBurdenAmount);
        }
    }
}
