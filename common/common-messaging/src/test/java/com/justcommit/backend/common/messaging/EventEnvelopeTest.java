package com.justcommit.backend.common.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-12T10:00:00+09:00");

  @Test
  @DisplayName("eventId · eventType · aggregateId가 빈 문자열이면 봉투를 만들지 않는다")
  void rejectsBlankText() {
    assertThatThrownBy(() -> envelope(" ", "TestEvent", 1, "7")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> envelope("id-1", "", 1, "7")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> envelope("id-1", "TestEvent", 1, "")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("version이 1보다 작으면 봉투를 만들지 않는다")
  void rejectsVersionBelowOne() {
    assertThatThrownBy(() -> envelope("id-1", "TestEvent", 0, "7")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("받은 JSON의 eventId가 비어 있으면 봉투로 바꾸지 못한다 (받는 쪽에서 형식 오류로 DLT)")
  void rejectsBlankEventIdFromJson() {
    String json = "{\"eventId\":\"\",\"eventType\":\"TestEvent\",\"version\":1,"
        + "\"occurredAt\":\"2026-11-12T10:00:00+09:00\",\"aggregateId\":\"7\",\"payload\":{}}";

    assertThatThrownBy(() -> new EventEnvelopeMapper().fromJson(json)).isInstanceOf(RuntimeException.class);
  }

  private static EventEnvelope envelope(String eventId, String eventType, int version, String aggregateId) {
    return new EventEnvelope(eventId, eventType, version, NOW, aggregateId, JsonNodeFactory.instance.objectNode());
  }
}
