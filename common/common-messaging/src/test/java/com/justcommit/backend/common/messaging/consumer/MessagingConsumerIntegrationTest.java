package com.justcommit.backend.common.messaging.consumer;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.IntegrationEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;

import static com.justcommit.backend.common.messaging.consumer.ConsumerTestApplication.TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 서비스가 yml에 적을 설정(ErrorHandlingDeserializer + EventEnvelopeDeserializer)을 그대로 넣고 확인
@SpringBootTest(classes = ConsumerTestApplication.class, properties = {
    "spring.kafka.consumer.group-id=consumer-test",
    "spring.kafka.consumer.auto-offset-reset=earliest",
    "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.ErrorHandlingDeserializer",
    "spring.kafka.consumer.properties.spring.deserializer.value.delegate.class="
        + "com.justcommit.backend.common.messaging.consumer.EventEnvelopeDeserializer"
})
@Testcontainers
class MessagingConsumerIntegrationTest {

  @Container
  @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

  @Autowired
  private KafkaTemplate<Object, Object> kafkaTemplate;

  @Autowired
  private EventEnvelopeMapper mapper;

  @Autowired
  private ConsumerTestApplication.TestListener listener;

  @Test
  @DisplayName("형식이 깨진 메시지는 리스너까지 가지 않고 원래 문자열 그대로 DLT로 간다")
  void malformedMessageGoesToDltWithoutRetry() throws Exception {
    String broken = "not-json-" + UUID.randomUUID();

    kafkaTemplate.send(TOPIC, "k1", broken).get();

    assertThat(awaitDlt(value -> value.equals(broken), Duration.ofSeconds(15))).isPresent();
  }

  @Test
  @DisplayName("봉투는 맞지만 payload를 서비스 클래스로 못 바꾸면 재시도 없이 DLT로 간다")
  void payloadMismatchGoesToDltWithoutRetry() throws Exception {
    String eventId = UUID.randomUUID().toString();
    // mode는 문자열이어야 하는데 객체라 TestPayload로 못 바꿈 (Jackson 예외)
    String json = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"TestEvent\",\"version\":1,"
        + "\"occurredAt\":\"2026-11-12T10:00:00+09:00\",\"aggregateId\":\"k2\","
        + "\"payload\":{\"key\":\"k2\",\"mode\":{\"wrong\":true}}}";

    kafkaTemplate.send(TOPIC, "k2", json).get();

    assertThat(awaitDlt(value -> value.contains(eventId), Duration.ofSeconds(15))).isPresent();
    assertThat(listener.attemptsOf(eventId)).isEqualTo(1);
  }

  @Test
  @DisplayName("처리가 계속 실패하면 1 · 2 · 4초 재시도(총 4번 시도) 뒤 봉투 그대로 DLT로 간다")
  void retriesThreeTimesThenDlt() throws Exception {
    EventEnvelope envelope = send("k3", "fail");

    Optional<ConsumerRecord<String, String>> dlt =
        awaitDlt(value -> value.contains(envelope.eventId()), Duration.ofSeconds(30));

    assertThat(dlt).isPresent();
    // DLT에도 같은 봉투 형식으로 들어가 나중에 다시 읽을 수 있음
    assertThat(mapper.fromJson(dlt.get().value()).eventId()).isEqualTo(envelope.eventId());
    assertThat(listener.attemptsOf(envelope.eventId())).isEqualTo(4);
  }

  @Test
  @DisplayName("같은 Key의 앞 메시지가 재시도하는 동안 뒤 메시지는 기다렸다가 그다음에 처리된다")
  void keepsOrderWithinSameKey() throws Exception {
    String key = "order-" + UUID.randomUUID();
    EventEnvelope first = send(key, "failTwice");
    EventEnvelope second = send(key, "ok");

    await().atMost(Duration.ofSeconds(20)).until(() -> listener.processed.contains(second.eventId()));

    List<String> processed = listener.processed;
    assertThat(processed.indexOf(first.eventId())).isLessThan(processed.indexOf(second.eventId()));
    assertThat(listener.attemptsOf(first.eventId())).isEqualTo(3);
    assertThat(listener.attemptsOf(second.eventId())).isEqualTo(1);
  }

  // 서비스가 Outbox로 보내는 것과 같은 봉투 JSON을 보냄
  private EventEnvelope send(String key, String mode) throws Exception {
    EventEnvelope envelope = mapper.wrap(new TestEvent(key, mode));
    kafkaTemplate.send(TOPIC, key, mapper.toJson(envelope)).get();
    return envelope;
  }

  // 원래토픽.DLT를 처음부터 읽어 조건에 맞는 메시지가 올 때까지 기다림
  private Optional<ConsumerRecord<String, String>> awaitDlt(Predicate<String> match, Duration timeout) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(TOPIC + MessagingConsumerAutoConfiguration.DLT_SUFFIX));
      long deadline = System.nanoTime() + timeout.toNanos();
      while (System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
          if (record.value() != null && match.test(record.value())) {
            return Optional.of(record);
          }
        }
      }
    }
    return Optional.empty();
  }

  record TestEvent(String key, String mode) implements IntegrationEvent {
    @Override
    public String topic() {
      return TOPIC;
    }

    @Override
    public String eventType() {
      return "TestEvent";
    }

    @Override
    public String aggregateId() {
      return key;
    }
  }
}
