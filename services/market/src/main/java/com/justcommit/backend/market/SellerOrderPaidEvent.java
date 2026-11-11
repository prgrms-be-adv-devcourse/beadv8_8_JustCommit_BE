package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.time.LocalDateTime;

public record SellerOrderPaidEvent(Long sellerOrderId, Long sellerId, Long buyerId, LocalDateTime paidAt)
        implements IntegrationEvent {

    @Override
    public String topic() {
        return KafkaTopics.MARKET_EVENTS;
    }

    @Override
    public String eventType() {
        return "SellerOrderPaid";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerOrderId);
    }
}
