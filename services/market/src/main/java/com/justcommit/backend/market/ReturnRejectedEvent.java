package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.time.LocalDateTime;
import java.util.Objects;

// 판매자 반품 거절 → 구매자에게 반품 거절 알림, 그 주문상품은 바로 구매 확정 (market.events.v1, notification, #383)
public record ReturnRejectedEvent(Long returnRequestId, Long orderItemId, Long orderId, Long sellerOrderId,
                                 Long buyerId, Long sellerId, String productName, ReturnDecidedBy decidedBy,
                                 LocalDateTime decidedAt) implements IntegrationEvent {

    public ReturnRejectedEvent {
        Objects.requireNonNull(sellerOrderId, "sellerOrderId");
        Objects.requireNonNull(decidedBy, "decidedBy");
    }

    @Override
    public String topic() {
        return KafkaTopics.MARKET_EVENTS;
    }

    @Override
    public String eventType() {
        return "ReturnRejected";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerOrderId);
    }
}
