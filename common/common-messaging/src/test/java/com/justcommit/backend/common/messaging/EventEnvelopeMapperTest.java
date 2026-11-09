package com.justcommit.backend.common.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeMapperTest {

  // 2026-11-12 10:00 (한국 시간)으로 고정
  private static final Clock CLOCK =
          Clock.fixed(Instant.parse("2026-11-12T01:00:00Z"), ZoneId.of("Asia/Seoul"));

  private final EventEnvelopeMapper mapper = new EventEnvelopeMapper(CLOCK);
  private final PurchaseConfirmed event = new PurchaseConfirmed(1024L, 7L, 32000L);

  @Test
  @DisplayName("이벤트로 봉투를 만들면 eventId는 새 UUID, 나머지는 이벤트에서 가져오고 발생 시각은 +09:00")
  void wrap() {
    EventEnvelope envelope = mapper.wrap(event);

    assertThat(UUID.fromString(envelope.eventId())).isNotNull();
    assertThat(envelope.eventType()).isEqualTo("PurchaseConfirmed");
    assertThat(envelope.version()).isEqualTo(1);
    assertThat(envelope.aggregateId()).isEqualTo("1024");
    assertThat(envelope.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-11-12T10:00:00+09:00"));
  }

  @Test
  @DisplayName("payload에는 record 필드만 들어가고 topic() · eventType() 같은 메서드는 들어가지 않는다")
  void payloadHasOnlyRecordFields() {
    JsonNode payload = mapper.wrap(event).payload();

    assertThat(payload.size()).isEqualTo(3);
    assertThat(payload.has("sellerOrderId")).isTrue();
    assertThat(payload.has("sellerId")).isTrue();
    assertThat(payload.has("amount")).isTrue();
    assertThat(payload.has("topic")).isFalse();
    assertThat(payload.has("eventType")).isFalse();
    assertThat(payload.has("aggregateId")).isFalse();
  }

  @Test
  @DisplayName("JSON은 events.md 예시와 같은 칸이고 Java 타입 정보가 없다")
  void jsonFormat() {
    String json = mapper.toJson(mapper.wrap(event));
    JsonNode node = JsonMapper.builder().build().readTree(json);

    assertThat(node.size()).isEqualTo(6);
    assertThat(node.has("eventId")).isTrue();
    assertThat(node.has("eventType")).isTrue();
    assertThat(node.has("version")).isTrue();
    assertThat(node.has("occurredAt")).isTrue();
    assertThat(node.has("aggregateId")).isTrue();
    assertThat(node.has("payload")).isTrue();
    assertThat(json).contains("\"occurredAt\":\"2026-11-12T10:00:00+09:00\"");
    assertThat(json).doesNotContain("@class").doesNotContain("PurchaseConfirmed$");
  }

  @Test
  @DisplayName("JSON을 다시 읽으면 봉투와 이벤트가 원래 값 그대로 나온다")
  void roundTrip() {
    EventEnvelope original = mapper.wrap(event);

    EventEnvelope read = mapper.fromJson(mapper.toJson(original));

    assertThat(read.eventId()).isEqualTo(original.eventId());
    assertThat(read.eventType()).isEqualTo(original.eventType());
    assertThat(read.version()).isEqualTo(original.version());
    assertThat(read.aggregateId()).isEqualTo(original.aggregateId());
    // 읽을 때 시간대가 바뀔 수 있어 같은 순간인지로 비교
    assertThat(read.occurredAt().isEqual(original.occurredAt())).isTrue();
    assertThat(mapper.readPayload(read, PurchaseConfirmed.class)).isEqualTo(event);
  }

  @Test
  @DisplayName("보내는 쪽이 payload에 새 칸을 추가해도 받는 쪽은 아는 칸만 읽는다")
  void ignoresUnknownPayloadFields() {
    String json = """
            {"eventId":"7c1e","eventType":"PurchaseConfirmed","version":1,
             "occurredAt":"2026-11-12T10:00:00+09:00","aggregateId":"1024",
             "payload":{"sellerOrderId":1024,"sellerId":7,"amount":32000,"newField":"x"}}
            """;

    EventEnvelope read = mapper.fromJson(json);

    assertThat(mapper.readPayload(read, PurchaseConfirmed.class)).isEqualTo(event);
  }

  @Test
  @DisplayName("빈 칸이 있으면 봉투를 만들 수 없다")
  void rejectsMissingField() {
    JsonNode payload = mapper.wrap(event).payload();

    assertThatThrownBy(() -> new EventEnvelope(null, "PurchaseConfirmed", 1,
            OffsetDateTime.now(CLOCK), "1024", payload))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("eventId");
  }

  // 서비스가 만드는 이벤트 record 예시 (IntegrationEvent 주석과 같음)
  record PurchaseConfirmed(Long sellerOrderId, Long sellerId, long amount) implements IntegrationEvent {
    @Override
    public String topic() {
      return KafkaTopics.MARKET_EVENTS;
    }

    @Override
    public String eventType() {
      return "PurchaseConfirmed";
    }

    @Override
    public String aggregateId() {
      return String.valueOf(sellerOrderId);
    }
  }
}
