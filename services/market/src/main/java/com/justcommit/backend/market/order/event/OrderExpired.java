package com.justcommit.backend.market.order.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.util.Objects;

/**
 * 결제 전 예약 해제 - 결제 대기 만료 (market.order-expired.v1, ADR 0016).
 * product는 이 주문이 잡은 상품을 다시 ACTIVE로 바꾼다.
 */
public record OrderExpired(Long orderId) implements IntegrationEvent {

  public OrderExpired {
    Objects.requireNonNull(orderId, "orderId");
  }

  @Override
  public String topic() {
    return KafkaTopics.MARKET_ORDER_EXPIRED;
  }

  @Override
  public String eventType() {
    return "OrderExpired";
  }

  @Override
  public String aggregateId() {
    return String.valueOf(orderId);
  }
}
