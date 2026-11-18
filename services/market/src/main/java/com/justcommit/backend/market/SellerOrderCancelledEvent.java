package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.time.LocalDateTime;
import java.util.Objects;

// 구매자가 판매자 주문을 전부 취소 → 판매자에게 취소 알림 (market.events.v1, notification)
public record SellerOrderCancelledEvent(Long orderId, Long sellerOrderId, Long sellerId, Long buyerId,
                                        LocalDateTime cancelledAt) implements IntegrationEvent {

    public SellerOrderCancelledEvent {
        Objects.requireNonNull(sellerOrderId, "sellerOrderId");
    }

    @Override
    public String topic() {
        return KafkaTopics.MARKET_EVENTS;
    }

    @Override
    public String eventType() {
        return "SellerOrderCancelled";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerOrderId);
    }
}
