package com.justcommit.backend.product.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

// market이 보낼 봉투 JSON을 테스트에서 만든다
final class EventMessages {

    private static final EventEnvelopeMapper MAPPER = new EventEnvelopeMapper();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private EventMessages() {
    }

    static String envelope(String eventType, Object aggregateId, Map<String, ?> payload) {
        return MAPPER.toJson(new EventEnvelope(
                UUID.randomUUID().toString(),
                eventType,
                1,
                OffsetDateTime.now(),
                String.valueOf(aggregateId),
                JSON.valueToTree(payload)));
    }
}
