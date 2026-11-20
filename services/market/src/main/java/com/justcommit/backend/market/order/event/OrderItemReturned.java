package com.justcommit.backend.market.order.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.util.Objects;

/**
 * 반품 뒤 판매 재개 (market.order-item-returned.v1, #384). 반품 환불이 확정된 트랜잭션에서 주문상품마다 낸다.
 * product는 ACTIVE + 숨김으로 바꾼다 (MarketEventListener.onOrderItemReturned).
 */
public record OrderItemReturned(Long orderId, Long orderItemId, Long productId) implements IntegrationEvent {

  public OrderItemReturned {
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(orderItemId, "orderItemId");
    Objects.requireNonNull(productId, "productId");
  }

  @Override
  public String topic() {
    return KafkaTopics.MARKET_ORDER_ITEM_RETURNED;
  }

  @Override
  public String eventType() {
    return "OrderItemReturned";
  }

  @Override
  public String aggregateId() {
    return String.valueOf(orderId);
  }
}
