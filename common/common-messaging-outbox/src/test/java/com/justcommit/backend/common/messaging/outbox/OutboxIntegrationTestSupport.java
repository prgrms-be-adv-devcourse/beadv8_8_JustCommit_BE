package com.justcommit.backend.common.messaging.outbox;

import com.justcommit.backend.common.messaging.IntegrationEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

// 실제 PostgreSQL · Kafka로 Outbox를 확인한다.
// 테이블은 권장 SQL(outbox/outbox_event.sql)로 만들고 ddl-auto: validate로 엔티티와 맞는지도 함께 확인한다.
// 스케줄러는 꺼 두고 테스트가 직접 부른다 (자동으로 돌면 결과가 그때그때 달라짐)
@SpringBootTest(classes = OutboxTestApplication.class, properties = {
    "outbox.scheduler.enabled=false",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:outbox/outbox_event.sql"
})
@Testcontainers
abstract class OutboxIntegrationTestSupport {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.1");

  @Autowired
  protected JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanTable() {
    jdbcTemplate.update("delete from outbox_event");
  }

  // 토픽을 처음부터 읽어 expected개가 모이거나 timeout이 지날 때까지 기다림
  protected List<ConsumerRecord<String, String>> consume(String topic, int expected, Duration timeout) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    List<ConsumerRecord<String, String>> records = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(topic));
      long deadline = System.nanoTime() + timeout.toNanos();
      while (records.size() < expected && System.nanoTime() < deadline) {
        consumer.poll(Duration.ofMillis(200)).forEach(records::add);
      }
    }
    return records;
  }

  protected String statusOf(UUID id) {
    return jdbcTemplate.queryForObject("select status from outbox_event where id = ?", String.class, id);
  }

  protected int rowCount() {
    return jdbcTemplate.queryForObject("select count(*) from outbox_event", Integer.class);
  }

  // 테스트마다 다른 토픽을 써서 앞 테스트의 메시지가 섞이지 않게
  protected static String newTopic() {
    return "outbox-test-" + UUID.randomUUID();
  }

  // 서비스의 이벤트 record 예시 (토픽을 테스트마다 바꾸려고 필드로 받음)
  record TestEvent(String topic, String orderId) implements IntegrationEvent {
    @Override
    public String eventType() {
      return "TestEvent";
    }

    @Override
    public String aggregateId() {
      return orderId;
    }
  }
}
