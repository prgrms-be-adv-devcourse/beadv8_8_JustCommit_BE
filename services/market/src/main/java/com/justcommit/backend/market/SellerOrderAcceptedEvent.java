package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.time.LocalDateTime;
import java.util.Objects;

// 판매자가 판매자 주문을 수락 → 구매자에게 수락 알림 (market.events.v1, notification, #288).
// rejectedItemCount는 이번에 함께 거절한 주문상품 수, 1 이상이면 일부 거절 (구매자가 먼저 취소한 상품은 세지 않음)
public record SellerOrderAcceptedEvent(Long orderId, Long sellerOrderId, Long sellerId, Long buyerId,
                                       int rejectedItemCount, LocalDateTime acceptedAt) implements IntegrationEvent {

    public SellerOrderAcceptedEvent {
        Objects.requireNonNull(sellerOrderId, "sellerOrderId");
    }

    @Override
    public String topic() {
        return KafkaTopics.MARKET_EVENTS;
    }

    @Override
    public String eventType() {
        return "SellerOrderAccepted";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerOrderId);
    }
}
