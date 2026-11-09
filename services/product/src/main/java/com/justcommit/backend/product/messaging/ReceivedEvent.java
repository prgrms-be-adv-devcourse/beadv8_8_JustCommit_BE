package com.justcommit.backend.product.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;

// 받은 봉투와 이 서비스의 클래스로 바꾼 payload
public record ReceivedEvent<T>(EventEnvelope envelope, T payload) {

    public String eventId() {
        return envelope.eventId();
    }
}
