package com.justcommit.backend.common.messaging.outbox;

import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * common-messaging-outbox를 의존한 서비스에 Outbox 발행 빈을 등록한다.
 * Outbox 엔티티 · 저장소는 서비스 패키지(com.justcommit.backend) 아래라 따로 설정 없이 잡힌다.
 */
@AutoConfiguration
@EnableScheduling
public class OutboxAutoConfiguration {

  // 서비스가 직접 만든 매퍼가 있으면 그걸 씀 (예: product 받는 쪽 설정)
  @Bean
  @ConditionalOnMissingBean
  EventEnvelopeMapper eventEnvelopeMapper() {
    return new EventEnvelopeMapper();
  }

  @Bean
  OutboxRelay outboxRelay(ProducerFactory<?, ?> producerFactory, OutboxEventRepository repository,
                          PlatformTransactionManager transactionManager) {
    return new OutboxRelay(outboxKafkaTemplate(producerFactory), repository, transactionManager,
        Executors.newVirtualThreadPerTaskExecutor(), Clock.systemUTC());
  }

  @Bean
  OutboxPublisher outboxPublisher(EntityManager entityManager, EventEnvelopeMapper mapper, OutboxRelay relay) {
    return new OutboxPublisher(entityManager, mapper, relay, Clock.systemUTC());
  }
  // outbox.scheduler.enabled = false면 재전송, 정리x (기본은 켜짐, 테스트에서 끔)
  @Bean
  @ConditionalOnBooleanProperty(name = "outbox.scheduler.enabled", matchIfMissing = true)
  OutboxScheduler outboxScheduler(OutboxEventRepository repository, OutboxRelay relay,
                                  PlatformTransactionManager transactionManager) {
    return new OutboxScheduler(repository, relay, transactionManager, Clock.systemUTC());
  }

  // 서비스의 Kafka 설정(주소 등)은 그대로 쓰고 직렬화만 문자열로 고정한 Outbox 전용 KafkaTemplate.
  // 빈으로 등록하지 않음: KafkaTemplate · ProducerFactory 빈을 만들면 Boot가 기본 KafkaTemplate을 만들지 않음
  private static KafkaTemplate<String, String> outboxKafkaTemplate(ProducerFactory<?, ?> producerFactory) {
    Map<String, Object> config = new HashMap<>(producerFactory.getConfigurationProperties());
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

    // 서비스가 Kafka 트랜잭션을 켜 두었어도 Outbox 발행은 Kafka 트랜잭션 없이 보냄
    config.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
    return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
  }
}
