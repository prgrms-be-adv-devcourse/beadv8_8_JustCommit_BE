package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

public record OrderItemCancelledForProductEvent(Long orderId, Long orderItemId, Long productId, OrderItemCancelReason reason)
        implements IntegrationEvent {

    @Override
    public String topic() {
        return KafkaTopics.MARKET_ORDER_ITEM_CANCELLED;
    }

    @Override
    public String eventType() {
        return "OrderItemCancelled";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(orderId);
    }
}
