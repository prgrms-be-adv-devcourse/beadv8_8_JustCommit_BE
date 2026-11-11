package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.time.LocalDateTime;

public record SellerOrderCancelledEvent(Long orderId, Long sellerOrderId, Long sellerId, Long buyerId, LocalDateTime cancelledAt)
        implements IntegrationEvent {

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
