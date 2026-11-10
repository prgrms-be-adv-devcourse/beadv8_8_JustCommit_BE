package com.justcommit.backend.common.messaging;

import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 모든 이벤트가 쓰는 공통 형식 (docs/conventions/events.md 「메시지 형식」).
 * 받는 쪽은 Java 타입 정보 없이 eventType 문자열로 자기 클래스에 매핑한다.
 *
 * @param eventId     이벤트 하나의 ID (Outbox PK와 같음, 로그 · DLT 추적용)
 * @param eventType   이벤트 이름 (예: PurchaseConfirmed)
 * @param version     payload 형식 버전
 * @param occurredAt  발생 시각 (시간대 포함)
 * @param aggregateId Kafka Key로 쓰는 업무 ID
 * @param payload     이벤트 내용 (서비스의 이벤트 record를 JSON으로 바꾼 것)
 */
public record EventEnvelope(
    String eventId,
    String eventType,
    int version,
    OffsetDateTime occurredAt,
    String aggregateId,
    JsonNode payload
) {
  // 빈 칸이 있는 봉투는 만들지 않음 (받는 쪽에서 원인을 찾기 어려워짐)
  public EventEnvelope {
    requireText(eventId, "eventId");
    requireText(eventType, "eventType");
    requireText(aggregateId, "aggregateId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(payload, "payload");
    if (version < 1) {
      throw new IllegalArgumentException("version은 1 이상이어야 합니다: " + version);
    }
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + "은(는) 비어 있을 수 없습니다");
    }
  }
}
