package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.time.LocalDateTime;

public record OrderItemAutoConfirmedEvent(Long orderItemId, Long orderId, Long sellerOrderId, Long buyerId,
                                          LocalDateTime confirmedAt) implements IntegrationEvent {

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
