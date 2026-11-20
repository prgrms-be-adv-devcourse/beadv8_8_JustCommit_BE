package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.time.LocalDateTime;
import java.util.Objects;

// 구매자가 반품을 신청 → 판매자에게 반품 신청 알림 (market.events.v1, notification, #382)
public record ReturnRequestedEvent(Long returnRequestId, Long orderItemId, Long orderId, Long sellerOrderId,
                                   Long buyerId, Long sellerId, String productName, ReturnReason reason,
                                   LocalDateTime requestedAt) implements IntegrationEvent {

    public ReturnRequestedEvent {
        Objects.requireNonNull(sellerOrderId, "sellerOrderId");
        Objects.requireNonNull(reason, "reason");
    }

    @Override
    public String topic() {
        return KafkaTopics.MARKET_EVENTS;
    }

    @Override
    public String eventType() {
        return "ReturnRequested";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerOrderId);
    }
}
