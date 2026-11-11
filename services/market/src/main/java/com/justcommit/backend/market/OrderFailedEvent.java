package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

public record OrderFailedEvent(Long orderId) implements IntegrationEvent {

    @Override
    public String topic() {
        return KafkaTopics.MARKET_ORDER_FAILED;
    }

    @Override
    public String eventType() {
        return "OrderFailed";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(orderId);
    }
}
