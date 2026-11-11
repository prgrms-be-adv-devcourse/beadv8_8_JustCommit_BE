package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

public record OrderPaidEvent(Long orderId) implements IntegrationEvent {

    @Override
    public String topic() {
        return KafkaTopics.MARKET_ORDER_PAID;
    }

    @Override
    public String eventType() {
        return "OrderPaid";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(orderId);
    }
}
