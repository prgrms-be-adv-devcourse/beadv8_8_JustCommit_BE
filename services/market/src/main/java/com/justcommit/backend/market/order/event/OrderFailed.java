package com.justcommit.backend.market.order.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.util.Objects;

/**
 * 결제 전 예약 해제 - 결제 확정 실패 (market.order-failed.v1, ADR 0016).
 * 예약 응답 타임아웃 · 지갑 없음 등으로 주문이 실패로 끝났을 때만 발행한다. 결제 결과를 모르는 동안은 발행하지 않는다.
 */
public record OrderFailed(Long orderId) implements IntegrationEvent {

  public OrderFailed {
    Objects.requireNonNull(orderId, "orderId");
  }

  @Override
  public String topic() {
    return KafkaTopics.MARKET_ORDER_FAILED;
  }

  @Override
  public String eventType() {
    return "OrderFailed";
  }

  @Override
  public String aggregateId() {
    return String.valueOf(orderId);
  }
}
