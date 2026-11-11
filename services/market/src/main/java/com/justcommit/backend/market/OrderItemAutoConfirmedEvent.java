package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.time.LocalDateTime;
import java.util.Objects;

// 주문상품 자동 구매 확정 알림용 (market.events.v1, notification). 정산은 PurchaseConfirmed만 받음
public record OrderItemAutoConfirmedEvent(Long orderItemId, Long orderId, Long sellerOrderId, Long buyerId,
                                          LocalDateTime confirmedAt) implements IntegrationEvent {

    public OrderItemAutoConfirmedEvent {
        Objects.requireNonNull(sellerOrderId, "sellerOrderId");
    }

    @Override
    public String topic() {
        return KafkaTopics.MARKET_EVENTS;
    }

    @Override
    public String eventType() {
        return "OrderItemAutoConfirmed";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerOrderId);
    }
}
