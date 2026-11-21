package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

// 판매자 주문이 끝남(수동 · 자동 확정, 반품 거절 모두), 구매 확정 상품이 1개 이상 → 정산 생성 · 구매 확정 알림
// (market.events.v1, settlement · notification). 구매 확정 상품이 없고 반품 완료만 있으면 SellerOrderReturned
// shippingFee: 배송비 이동이 정산 지급 때로 바뀌어(ShipmentPickedUp 없음) 정산이 여기서 배송비를 받는다
// sellerBurdenAmount: 판매자 부담금 = 이 판매자 주문의 반품 완료된 반품 배송비 합 (#383). 없으면 0
public record PurchaseConfirmedEvent(Long sellerOrderId, Long sellerId, BigDecimal saleAmount, BigDecimal shippingFee,
                                     BigDecimal sellerBurdenAmount, LocalDateTime confirmedAt,
                                     String itemSummary) implements IntegrationEvent {

    public PurchaseConfirmedEvent {
        Objects.requireNonNull(sellerOrderId, "sellerOrderId");
        Objects.requireNonNull(saleAmount, "saleAmount");
        Objects.requireNonNull(shippingFee, "shippingFee");
        sellerBurdenAmount = sellerBurdenAmount == null ? BigDecimal.ZERO : sellerBurdenAmount;
    }

    @Override
    public String topic() {
        return KafkaTopics.MARKET_EVENTS;
    }

    @Override
    public String eventType() {
        return "PurchaseConfirmed";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerOrderId);
    }
}
