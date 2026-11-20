package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

// 판매자 주문이 끝났는데 구매 확정 상품이 없고 반품 완료 상품만 있음(전부 반품) → 음수 정산 생성 (market.events.v1, settlement, #383).
// 전부 반품 = 취소되지 않은 주문상품이 모두 반품 완료. 수거 전 일부 취소 + 나머지 반품도 해당한다.
// sellerBurdenAmount: 판매자 부담금 = 반품 완료된 반품 배송비 합 + 원래 배송비 (원래 배송비는 구매자에게 환불됨)
// itemSummary: 반품 완료된 상품 요약 (첫 상품명 또는 「첫 상품명 외 N건」)
public record SellerOrderReturnedEvent(Long sellerOrderId, Long sellerId, BigDecimal sellerBurdenAmount,
                                       LocalDateTime returnedAt, String itemSummary) implements IntegrationEvent {

    public SellerOrderReturnedEvent {
        Objects.requireNonNull(sellerOrderId, "sellerOrderId");
        Objects.requireNonNull(sellerBurdenAmount, "sellerBurdenAmount");
    }

    @Override
    public String topic() {
        return KafkaTopics.MARKET_EVENTS;
    }

    @Override
    public String eventType() {
        return "SellerOrderReturned";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerOrderId);
    }
}
