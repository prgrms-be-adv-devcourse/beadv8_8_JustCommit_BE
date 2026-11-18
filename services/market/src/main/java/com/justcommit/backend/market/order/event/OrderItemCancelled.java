package com.justcommit.backend.market.order.event;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.KafkaTopics;

import java.util.Objects;

/**
 * 판매 재개 (market.order-item-cancelled.v1, ADR 0016). 환불이 성공한 뒤 발행하고, 주문 전체 취소도 주문상품마다 낸다.
 * product는 BUYER면 ACTIVE, SELLER · AUTO면 ACTIVE + 숨김으로 바꾼다.
 */
public record OrderItemCancelled(Long orderId, Long orderItemId, Long productId, CancelReason reason)
    implements IntegrationEvent {

  public OrderItemCancelled {
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(orderItemId, "orderItemId");
    Objects.requireNonNull(productId, "productId");
    Objects.requireNonNull(reason, "reason");
  }

  public enum CancelReason {
    BUYER,  // 구매자 취소
    SELLER, // 판매자 거절 (#283)
    AUTO    // 24시간 자동 거절 (#283)
  }

  @Override
  public String topic() {
    return KafkaTopics.MARKET_ORDER_ITEM_CANCELLED;
  }

  @Override
  public String eventType() {
    return "OrderItemCancelled";
  }

  @Override
  public String aggregateId() {
    return String.valueOf(orderId);
  }
}
