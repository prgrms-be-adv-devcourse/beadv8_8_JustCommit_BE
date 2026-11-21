package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

// 반품 완료(반품 환불 확정) → 구매자에게 환불 완료 알림 (market.events.v1, notification, #384).
// product용 판매 재개(order.event.OrderItemReturned, market.order-item-returned.v1)와 eventType 이름은 같지만 토픽이 다르다.
// refundAmount: 이 주문상품으로 구매자에게 환불한 상품 금액 (배송비 제외)
public record OrderItemReturnedEvent(Long returnRequestId, Long orderItemId, Long orderId, Long sellerOrderId,
                                     Long buyerId, Long sellerId, String productName, BigDecimal refundAmount,
                                     LocalDateTime returnedAt) implements IntegrationEvent {

    public OrderItemReturnedEvent {
        Objects.requireNonNull(sellerOrderId, "sellerOrderId");
        Objects.requireNonNull(refundAmount, "refundAmount");
    }

    @Override
    public String topic() {
        return KafkaTopics.MARKET_EVENTS;
    }

    @Override
    public String eventType() {
        return "OrderItemReturned";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(sellerOrderId);
    }
}
