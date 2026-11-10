package com.justcommit.backend.product.config;

import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.product.messaging.MalformedEventException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

// 소비 실패 처리 (docs/conventions/events.md 「실패 처리」, ADR 0017)
// shortcut: common-messaging에 소비 실패 기본 설정이 생기면 이 클래스를 지우고 공통 설정을 쓴다
@Slf4j
@Configuration
public class KafkaConsumerConfig {

    private static final String DLT_SUFFIX = ".DLT";

    @Bean
    EventEnvelopeMapper eventEnvelopeMapper() {
        return new EventEnvelopeMapper();
    }

    // 제자리 재시도 1 · 2 · 4초 → 원래토픽.DLT. 형식이 깨진 메시지는 재시도 없이 바로 DLT
    // Boot가 이 빈을 기본 리스너 컨테이너에 붙인다
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations,
                // 파티션은 -1: DLT 파티션 수가 원래 토픽과 달라도 보낼 수 있게 Kafka가 고름
                (record, ex) -> {
                    // payload는 로그에 남기지 않음 (토픽 · 위치 · 원인 종류만, 파싱 오류 메시지에는 본문 일부가 섞일 수 있음)
                    log.error("이벤트 처리 실패 → DLT: topic={}, partition={}, offset={}, key={}, cause={}",
                            record.topic(), record.partition(), record.offset(), record.key(),
                            NestedExceptionUtils.getMostSpecificCause(ex).getClass().getName());
                    return new TopicPartition(record.topic() + DLT_SUFFIX, -1);
                });

        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxAttempts(3); // 1초 → 2초 → 4초 뒤 DLT

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(MalformedEventException.class);
        return handler;
    }
}
