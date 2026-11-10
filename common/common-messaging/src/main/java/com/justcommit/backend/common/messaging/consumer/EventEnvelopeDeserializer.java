package com.justcommit.backend.common.messaging.consumer;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import org.apache.kafka.common.serialization.Deserializer;

import java.nio.charset.StandardCharsets;

/**
 * Kafka 메시지(바이트) → 봉투.
 * 받는 쪽 공통 설정이 ErrorHandlingDeserializer로 감싸서 쓴다: 여기서 실패하면 재시도 없이 바로 원래토픽.DLT.
 */
public class EventEnvelopeDeserializer implements Deserializer<EventEnvelope> {
  private final EventEnvelopeMapper mapper;

  // yml의 spring.deserializer.value.delegate.class로 Kafka가 직접 만들 때 쓰는 생성자
  public EventEnvelopeDeserializer() {
    this(new EventEnvelopeMapper());
  }

  public EventEnvelopeDeserializer(EventEnvelopeMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public EventEnvelope deserialize(String topic, byte[] data) {
    // 모든 이벤트는 봉투라 값이 없는 메시지는 형식 오류로 봄
    if (data == null) {
      throw new IllegalArgumentException("값이 없는 메시지: topic=" + topic);
    }
    return mapper.fromJson(new String(data, StandardCharsets.UTF_8));
  }
}
