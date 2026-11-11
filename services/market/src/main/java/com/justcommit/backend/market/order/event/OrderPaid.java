package com.justcommit.backend.market.order.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.util.Objects;

/**
 * 판매 완료 (market.order-paid.v1, ADR 0016). 결제 · 재결제가 끝나면 발행한다.
 * product는 이 주문이 잡은 상품만 SOLD_OUT으로 바꾼다.
 */
public record OrderPaid(Long orderId) implements IntegrationEvent {

  public OrderPaid {
    Objects.requireNonNull(orderId, "orderId");
  }

  @Override
  public String topic() {
    return KafkaTopics.MARKET_ORDER_PAID;
  }

  @Override
  public String eventType() {
    return "OrderPaid";
  }

  @Override
  public String aggregateId() {
    return String.valueOf(orderId);
  }
}
