package com.justcommit.backend.common.messaging;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 이벤트 ↔ 봉투 ↔ JSON 변환.
 * 서비스마다 Jackson 설정이 달라도 메시지 형식은 같아야 해서, 이 클래스 안에 JsonMapper를 따로 둔다.
 */
public final class EventEnvelopeMapper {

  // 발생 시각은 한국 시간대로 기록 (정책 10.13-5: occurredAt +09:00)
  private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

  private final JsonMapper jsonMapper;
  private final Clock clock;

  public EventEnvelopeMapper() {
    this(Clock.system(ZONE));
  }

  // 테스트에서 시각을 고정하려고 Clock을 받는 생성자를 둠
  public EventEnvelopeMapper(Clock clock) {
    this.clock = clock;
    this.jsonMapper = JsonMapper.builder()
        // 보내는 쪽이 payload에 칸을 새로 추가해도 받는 쪽이 깨지지 않게
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
  }

  // 보내는 쪽: 서비스 이벤트 -> 봉투 (eventId를 새로 만들고 발생 시각을 기록)
  public EventEnvelope wrap(IntegrationEvent event) {
    return new EventEnvelope(
        UUID.randomUUID().toString(),
        event.eventType(),
        event.version(),
        OffsetDateTime.now(clock),
        event.aggregateId(),
        jsonMapper.valueToTree(event));
  }

  public String toJson(EventEnvelope envelope) {
    return jsonMapper.writeValueAsString(envelope);
  }

  public EventEnvelope fromJson(String json) {
    return jsonMapper.readValue(json, EventEnvelope.class);
  }

  // 받는 쪽: eventType을 보고 고른 자기 클래스로 payload를 바꿈
  public <T> T readPayload(EventEnvelope envelope, Class<T> type) {
    return jsonMapper.treeToValue(envelope.payload(), type);
  }
}
