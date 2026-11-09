package com.justcommit.backend.common.messaging;

/**
 * 서비스 사이에 Kafka로 주고받는 이벤트.
 * 각 서비스의 이벤트 record가 구현한다 (record는 서비스마다 따로 두고 공통에 넣지 않음).
 * record의 필드가 봉투의 payload가 되고, 아래 메서드는 payload에 들어가지 않는다.
 *
 * 예:
 * public record PurchaseConfirmed(Long sellerOrderId, Long sellerId, long amount)
 *     implements IntegrationEvent {
 *   public String topic() { return KafkaTopics.MARKET_EVENTS; }
 *   public String eventType() { return "PurchaseConfirmed"; }
 *   public String aggregateId() { return String.valueOf(sellerOrderId); }
 * }
 *
 */
public interface IntegrationEvent {

  // 보낼 토픽 (KafkaTopics 상수)
  String topic();

  // 받는 쪽이 자기 클래스를 고르는 이름. 클래스 이름에서 자동으로 뽑지 않고 직접 적는다
  // (클래스 이름을 바꾸거나 옮겨도 받는 쪽이 계속 읽을 수 있게)
  String eventType();

  // Kafka Key. 같은 Key는 같은 파티션으로 가서 순서가 지켜진다
  String aggregateId();

  // payload 형식이 바뀌면 올린다
  default int version() {
    return 1;
  }
}
