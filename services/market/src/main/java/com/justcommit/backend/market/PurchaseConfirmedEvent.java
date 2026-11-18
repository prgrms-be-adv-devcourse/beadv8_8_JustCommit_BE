package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

// 판매자 주문이 끝남(수동 · 자동 확정 모두) → 정산 생성 · 구매 확정 알림 (market.events.v1, settlement · notification)
// shippingFee: 배송비 이동이 정산 지급 때로 바뀌어(ShipmentPickedUp 없음) 정산이 여기서 배송비를 받는다
public record PurchaseConfirmedEvent(Long sellerOrderId, Long sellerId, BigDecimal saleAmount, BigDecimal shippingFee,
                                     LocalDateTime confirmedAt, String itemSummary) implements IntegrationEvent {

    public PurchaseConfirmedEvent {
        Objects.requireNonNull(sellerOrderId, "sellerOrderId");
        Objects.requireNonNull(saleAmount, "saleAmount");
        Objects.requireNonNull(shippingFee, "shippingFee");
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
