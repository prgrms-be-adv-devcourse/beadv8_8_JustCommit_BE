package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.time.LocalDateTime;

public record OrderItemCancelledEvent(Long orderItemId, Long orderId, Long sellerOrderId, Long sellerId,
                                      String productName, LocalDateTime cancelledAt) implements IntegrationEvent {

    @Override
    public String topic() {
        return KafkaTopics.MARKET_EVENTS;
    }

    @Override
    public String eventType() {
        return "OrderItemCancelled";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerOrderId);
    }
}
