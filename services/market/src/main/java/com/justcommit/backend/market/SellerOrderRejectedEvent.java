package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.time.LocalDateTime;
import java.util.Objects;

// 판매자 주문의 남은 상품이 모두 거절됨 (market.events.v1, notification, #288). 판매자 거절 SELLER, 24시간 자동 거절 AUTO.
// 일부만 거절이면 이 이벤트가 아니라 SellerOrderAcceptedEvent의 rejectedItemCount
public record SellerOrderRejectedEvent(Long orderId, Long sellerOrderId, Long sellerId, Long buyerId,
                                       SellerOrderRejectReason reason, LocalDateTime rejectedAt)
        implements IntegrationEvent {

    public SellerOrderRejectedEvent {
        Objects.requireNonNull(sellerOrderId, "sellerOrderId");
        Objects.requireNonNull(reason, "reason");
    }

    @Override
    public String topic() {
        return KafkaTopics.MARKET_EVENTS;
    }

    @Override
    public String eventType() {
        return "SellerOrderRejected";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerOrderId);
    }
}
