package com.justcommit.backend.market;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.time.LocalDateTime;
import java.util.Objects;

// 구매자가 판매자 주문의 일부 상품만 취소 -> 판매자에게 취소 알림(market.events.v1, notification)
// 판매자 주문이 전부 취소되면 이것 대신 SellerOrderCancelled가 나감
// product용 판매 재개(order.event.OrderItemCancelled, market.order-item-cancelled.v1)와 eventType 이름은 같지만 토픽이 다르다
public record OrderItemCancelledEvent(
    Long orderItemId, Long orderId, Long sellerOrderId, Long sellerId,
    String productName, LocalDateTime cancelledAt) implements IntegrationEvent  {

  public OrderItemCancelledEvent {
    Objects.requireNonNull(sellerOrderId, "sellerOrderId");
  }

  @Override
  public String topic() {
    return KafkaTopics.MARKET_EVENTS;
  }
  @Override
  public String eventType() {
    return "OrderItemCancelled";
  }

  @Override
  public String aggregateId() {
    return String.valueOf(sellerOrderId);
  }
}
