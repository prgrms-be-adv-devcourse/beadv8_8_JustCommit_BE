package com.justcommit.backend.product.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 받은 문자열 → 봉투 → payload. 읽지 못하면 MalformedEventException (재시도 없이 DLT)
@Component
@RequiredArgsConstructor
public class EventReader {

    private final EventEnvelopeMapper mapper;

    public <T> ReceivedEvent<T> read(String message, Class<T> payloadType) {
        EventEnvelope envelope;
        try {
            envelope = mapper.fromJson(message);
        } catch (RuntimeException e) {
            throw new MalformedEventException("봉투를 읽을 수 없음", e);
        }
        try {
            return new ReceivedEvent<>(envelope, mapper.readPayload(envelope, payloadType));
        } catch (RuntimeException e) {
            // payload record의 필수 칸 검사(requireNonNull)에서 난 예외도 여기로 옴
            throw new MalformedEventException(
                    "payload를 읽을 수 없음: eventId=" + envelope.eventId() + ", eventType=" + envelope.eventType(), e);
        }
    }
}
