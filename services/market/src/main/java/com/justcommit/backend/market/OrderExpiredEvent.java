package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

public record OrderExpiredEvent(Long orderId) implements IntegrationEvent {

    @Override
    public String topic() {
        return KafkaTopics.MARKET_ORDER_EXPIRED;
    }

    @Override
    public String eventType() {
        return "OrderExpired";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(orderId);
    }
}
