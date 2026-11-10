package com.justcommit.backend.common.messaging.outbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class OutboxIntegrationTest extends OutboxIntegrationTestSupport {

  @Autowired
  private OutboxPublisher publisher;

  @Autowired
  private OutboxRelay relay;

  @Autowired
  private OutboxEventRepository repository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Test
  @DisplayName("커밋되면 봉투 JSON이 Key = aggregateId로 Kafka에 도착하고 PUBLISHED가 된다")
  void publishesAfterCommit() {
    String topic = newTopic();

    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        publisher.publish(new TestEvent(topic, "1024")));

    List<ConsumerRecord<String, String>> records = consume(topic, 1, Duration.ofSeconds(15));
    assertThat(records).hasSize(1);
    assertThat(records.getFirst().key()).isEqualTo("1024");
    assertThat(records.getFirst().value())
        .contains("\"eventType\":\"TestEvent\"")
        .contains("\"aggregateId\":\"1024\"");

    UUID id = jdbcTemplate.queryForObject("select id from outbox_event", UUID.class);
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertThat(statusOf(id)).isEqualTo("PUBLISHED"));
  }

  @Test
  @DisplayName("롤백되면 행도 남지 않고 Kafka로도 보내지 않는다")
  void rollbackDoesNotPublish() {
    String topic = newTopic();

    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      publisher.publish(new TestEvent(topic, "1024"));
      status.setRollbackOnly();
    });

    assertThat(rowCount()).isZero();
    assertThat(consume(topic, 1, Duration.ofSeconds(3))).isEmpty();
  }

  @Test
  @DisplayName("트랜잭션 밖에서 발행하면 바로 예외 (저장과 발행이 따로 놀지 않게)")
  void requiresTransaction() {
    assertThatThrownBy(() -> publisher.publish(new TestEvent(newTopic(), "1024")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("재전송: 5초 지난 PENDING을 보내고 PUBLISHED로 바꾼다")
  void retrySendsPending() {
    String topic = newTopic();
    UUID id = insertPending(topic, "7", "1 minute");

    scheduler().retry();

    assertThat(statusOf(id)).isEqualTo("PUBLISHED");
    List<ConsumerRecord<String, String>> records = consume(topic, 1, Duration.ofSeconds(10));
    assertThat(records).hasSize(1);
    assertThat(records.getFirst().key()).isEqualTo("7");
  }

  @Test
  @DisplayName("재전송: 만든 지 5초가 안 된 행은 건드리지 않는다 (커밋 직후 발행과 겹치지 않게)")
  void retrySkipsRecentRows() {
    UUID id = insertPending(newTopic(), "7", "0 seconds");

    scheduler().retry();

    assertThat(statusOf(id)).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("두 인스턴스가 동시에 재전송해도 행마다 한 번씩만 보낸다 (FOR UPDATE SKIP LOCKED)")
  void twoInstancesSendEachRowOnce() throws Exception {
    String topic = newTopic();
    int rows = 20;
    for (int i = 0; i < rows; i++) {
      insertPending(topic, "order-" + i, "1 minute");
    }

    OutboxScheduler first = scheduler();
    OutboxScheduler second = scheduler();
    CyclicBarrier start = new CyclicBarrier(2);
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<?> a = pool.submit(() -> { awaitBarrier(start); first.retry(); });
      Future<?> b = pool.submit(() -> { awaitBarrier(start); second.retry(); });
      a.get();
      b.get();
    }

    Integer published = jdbcTemplate.queryForObject(
        "select count(*) from outbox_event where status = 'PUBLISHED'", Integer.class);
    assertThat(published).isEqualTo(rows);
    // 하나라도 두 번 나갔으면 rows보다 많이 읽힘
    assertThat(consume(topic, rows + 1, Duration.ofSeconds(5))).hasSize(rows);
  }

  @Test
  @DisplayName("실패가 5번째가 되면 FAILED, 그 전까지는 PENDING으로 남아 재전송된다")
  void failsAfterMaxAttempts() {
    UUID id = insertPending(newTopic(), "7", "1 minute");
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    for (int i = 1; i < OutboxRelay.MAX_ATTEMPTS; i++) {
      transaction.executeWithoutResult(status -> repository.recordFailure(id, "test error", OutboxRelay.MAX_ATTEMPTS));
    }
    assertThat(statusOf(id)).isEqualTo("PENDING");

    transaction.executeWithoutResult(status -> repository.recordFailure(id, "test error", OutboxRelay.MAX_ATTEMPTS));
    assertThat(statusOf(id)).isEqualTo("FAILED");
    assertThat(jdbcTemplate.queryForObject("select attempt_count from outbox_event where id = ?", Integer.class, id))
        .isEqualTo(OutboxRelay.MAX_ATTEMPTS);
  }

  @Test
  @DisplayName("정리: 발행이 끝난 지 7일 지난 행만 지운다")
  void cleanUpDeletesOnlyOldPublished() {
    UUID old = insertPublished("8 days");
    UUID recent = insertPublished("1 day");

    scheduler().cleanUp();

    assertThat(jdbcTemplate.queryForObject("select count(*) from outbox_event where id = ?", Integer.class, old)).isZero();
    assertThat(jdbcTemplate.queryForObject("select count(*) from outbox_event where id = ?", Integer.class, recent)).isOne();
  }

  // 스케줄러는 테스트에서 꺼 두었으므로 직접 만듦 (두 개 만들면 파드 두 개를 흉내 냄)
  private OutboxScheduler scheduler() {
    return new OutboxScheduler(repository, relay, transactionManager, Clock.systemUTC());
  }

  private UUID insertPending(String topic, String aggregateId, String age) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("insert into outbox_event (id, aggregate_id, topic, event_type, payload, status, attempt_count, created_at) "
        + "values (?, ?, ?, 'TestEvent', '{}', 'PENDING', 0, now() - cast(? as interval))", id, aggregateId, topic, age);
    return id;
  }

  private UUID insertPublished(String publishedAgo) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("insert into outbox_event (id, aggregate_id, topic, event_type, payload, status, attempt_count, published_at, created_at) "
        + "values (?, '7', 'outbox-test', 'TestEvent', '{}', 'PUBLISHED', 0, now() - cast(? as interval), now() - cast(? as interval))",
        id, publishedAgo, publishedAgo);
    return id;
  }

  private static void awaitBarrier(CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
