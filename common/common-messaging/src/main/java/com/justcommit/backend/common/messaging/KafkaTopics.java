package com.justcommit.backend.common.messaging;

/**
 * Kafka 토픽 이름 (docs/reference/kafka-topics.md 13개와 같아야 함).
 * 토픽을 바꾸거나 추가하면 그 문서도 함께 고친다.
 */
public final class KafkaTopics {

  // 도메인 토픽 8개
  public static final String MEMBER_EVENTS = "member.events.v1";
  public static final String MEMBER_ADDRESS_EVENTS = "member.address.events.v1";
  public static final String ORDER_EVENTS = "order.events.v1";
  public static final String SHIPMENT_EVENTS = "shipment.events.v1";
  public static final String PRODUCT_EVENTS = "product.events.v1";
  public static final String PRODUCT_REVIEW = "product.review.v1";
  public static final String SPECIES_EVENTS = "species.events.v1";
  public static final String SETTLEMENT_EVENTS = "settlement.events.v1";

  // market → product 토픽 5개 (이벤트마다 하나, ADR 0016)
  public static final String MARKET_ORDER_PAID = "market.order-paid.v1";
  public static final String MARKET_ORDER_EXPIRED = "market.order-expired.v1";
  public static final String MARKET_ORDER_FAILED = "market.order-failed.v1";
  public static final String MARKET_ORDER_ITEM_CANCELLED = "market.order-item-cancelled.v1";
  public static final String MARKET_ORDER_ITEM_RETURNED = "market.order-item-returned.v1";

  private KafkaTopics() {
  }
}
