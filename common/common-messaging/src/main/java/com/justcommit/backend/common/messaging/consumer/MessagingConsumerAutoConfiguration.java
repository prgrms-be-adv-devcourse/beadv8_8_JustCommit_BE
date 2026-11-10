package com.justcommit.backend.common.messaging.consumer;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * 이벤트를 받는 쪽 공통 설정 (docs/conventions/events.md 「실패 처리」, ADR 0017).
 * 처리 실패는 1 · 2 · 4초 제자리 재시도 후 원래토픽.DLT, 형식 오류는 재시도 없이 바로 원래토픽.DLT.
 * 서비스가 에러 처리기를 직접 만들었으면 그걸 쓴다.
 */
@AutoConfiguration
public class MessagingConsumerAutoConfiguration {

  static final String DLT_SUFFIX = ".DLT";

  // 재시도 1,2,4초
  static final long FIRST_RETRY_MILLIS = 1_000L;
  static final int MAX_RETRIES = 3;
  private static final Logger log = LoggerFactory.getLogger(MessagingConsumerAutoConfiguration.class);

  // 서비스가 직접 만든 매퍼가 있으면 그걸 씀 (Outbox도 이 빈을 씀)
  @Bean
  @ConditionalOnMissingBean
  EventEnvelopeMapper eventEnvelopeMapper() {
    return new EventEnvelopeMapper();
  }

  // DLT 전송 전용 프로듀서 (종료 때 Spring이 close()로 정리)
  @Bean
  @ConditionalOnMissingBean(CommonErrorHandler.class)
  DeadLetterProducer deadLetterProducer(ProducerFactory<?, ?> producerFactory, EventEnvelopeMapper mapper) {
    return new DeadLetterProducer(producerFactory, mapper);
  }

  // Boot가 이 처리기를 기본 리스너 설정에 자동으로 붙임 (처리기 빈이 하나일 때)
  @Bean
  @ConditionalOnMissingBean(CommonErrorHandler.class)
  DefaultErrorHandler eventErrorHandler(DeadLetterProducer deadLetterProducer) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(deadLetterProducer.template(),
        (record, exception) -> {
          String eventId = record.value() instanceof EventEnvelope envelope ? envelope.eventId() : null;
          // payload와 예외 메시지(본문 일부가 섞일 수 있음)는 남기지 않음. 위치와 원인 종류만
          log.error("이벤트 처리 실패 → DLT: topic={}, partition={}, offset={}, key={}, eventId={}, cause={}",
              record.topic(), record.partition(), record.offset(), record.key(), eventId,
              NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName());
          // 파티션 -1: DLT 파티션 수가 원래 토픽과 달라도 Kafka가 고르게 함
          return new TopicPartition(record.topic() + DLT_SUFFIX, -1);
        });

    ExponentialBackOff backOff = new ExponentialBackOff(FIRST_RETRY_MILLIS, 2.0);
    backOff.setMaxAttempts(MAX_RETRIES);

    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
    // payload를 서비스 클래스로 못 바꾸는 경우: 다시 해도 같으므로 바로 DLT (역직렬화 실패는 기본으로 재시도 안 함)
    handler.addNotRetryableExceptions(JacksonException.class);
    return handler;
  }
}
