package com.justcommit.backend.common.messaging.consumer;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import org.apache.kafka.common.serialization.Serializer;

import java.nio.charset.StandardCharsets;

/**
 * 봉투 → Kafka 메시지(바이트).
 * 처리에 실패한 메시지를 원래토픽.DLT로 보낼 때 받은 봉투를 같은 형식으로 되돌려 보내는 데 쓴다.
 */
public class EventEnvelopeSerializer implements Serializer<EventEnvelope> {

  private final EventEnvelopeMapper mapper;

  public EventEnvelopeSerializer(EventEnvelopeMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public byte[] serialize(String topic, EventEnvelope envelope) {
    return envelope == null ? null : mapper.toJson(envelope).getBytes(StandardCharsets.UTF_8);
  }
}
