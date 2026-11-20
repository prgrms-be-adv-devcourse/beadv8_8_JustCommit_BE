package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.time.LocalDateTime;
import java.util.Objects;

// 반품 승인(판매자 SELLER, 48시간 자동 승인 AUTO) → 구매자에게 반품 승인 알림 (market.events.v1, notification, #383)
public record ReturnApprovedEvent(Long returnRequestId, Long orderItemId, Long orderId, Long sellerOrderId,
                                 Long buyerId, Long sellerId, String productName, ReturnDecidedBy decidedBy,
                                 LocalDateTime decidedAt) implements IntegrationEvent {

    public ReturnApprovedEvent {
        Objects.requireNonNull(sellerOrderId, "sellerOrderId");
        Objects.requireNonNull(decidedBy, "decidedBy");
    }

    @Override
    public String topic() {
        return KafkaTopics.MARKET_EVENTS;
    }

    @Override
    public String eventType() {
        return "ReturnApproved";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerOrderId);
    }
}
