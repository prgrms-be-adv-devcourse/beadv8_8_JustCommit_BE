package com.justcommit.backend.common.messaging.consumer;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

// 받는 쪽 공통 설정을 확인하는 테스트 전용 앱
@SpringBootApplication
class ConsumerTestApplication {

  static final String TOPIC = "consumer-test";

  // 서비스의 리스너 흉내: payload의 mode로 성공 · 실패를 정함
  @Component
  static class TestListener {

    final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    final List<String> processed = new CopyOnWriteArrayList<>();
    private final EventEnvelopeMapper mapper;

    TestListener(EventEnvelopeMapper mapper) {
      this.mapper = mapper;
    }

    @KafkaListener(topics = TOPIC)
    public void on(EventEnvelope envelope) {
      int attempt = attempts.computeIfAbsent(envelope.eventId(), id -> new AtomicInteger()).incrementAndGet();
      TestPayload payload = mapper.readPayload(envelope, TestPayload.class);
      switch (payload.mode()) {
        case "fail" -> throw new IllegalStateException("처리 실패");
        case "failTwice" -> {
          if (attempt <= 2) {
            throw new IllegalStateException("일시 실패");
          }
        }
        default -> {
        }
      }
      processed.add(envelope.eventId());
    }

    int attemptsOf(String eventId) {
      AtomicInteger count = attempts.get(eventId);
      return count == null ? 0 : count.get();
    }
  }

  record TestPayload(String key, String mode) {
  }
}
