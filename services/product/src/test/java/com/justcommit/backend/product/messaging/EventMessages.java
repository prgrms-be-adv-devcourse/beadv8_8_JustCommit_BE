package com.justcommit.backend.product.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

// 보내는 쪽(market · member)이 만들 봉투를 테스트에서 만든다
final class EventMessages {

    private static final EventEnvelopeMapper MAPPER = new EventEnvelopeMapper();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private EventMessages() {
    }

    // 리스너에 바로 넘길 봉투 (단위 테스트)
    static EventEnvelope event(String eventType, Object aggregateId, Map<String, ?> payload) {
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                eventType,
                1,
                OffsetDateTime.now(),
                String.valueOf(aggregateId),
                JSON.valueToTree(payload));
    }

    // Kafka로 보낼 봉투 JSON (통합 테스트)
    static String envelope(String eventType, Object aggregateId, Map<String, ?> payload) {
        return MAPPER.toJson(event(eventType, aggregateId, payload));
    }
}
