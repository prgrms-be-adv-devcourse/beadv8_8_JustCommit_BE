package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.time.LocalDateTime;
import java.util.Objects;

// 판매자에게 수락 요청 알림 (market.events.v1, notification)
public record SellerOrderPaidEvent(Long sellerOrderId, Long sellerId, Long buyerId, LocalDateTime paidAt)
        implements IntegrationEvent {

    public SellerOrderPaidEvent {
        Objects.requireNonNull(sellerOrderId, "sellerOrderId");
    }

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
