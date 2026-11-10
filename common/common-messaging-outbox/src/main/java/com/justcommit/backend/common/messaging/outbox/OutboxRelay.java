package com.justcommit.backend.common.messaging.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 행을 Kafka로 보내고 결과를 기록한다 (Outbox 릴레이).
 * Kafka가 저장했다고 응답(ack)한 경우에만 PUBLISHED, 실패 · 시간 초과는 실패 횟수만 올리고 PENDING 유지.
 * 보냈는데 기록에 실패하면 다시 보낼 수 있다 (최소 1회 전달, 받는 쪽이 업무 키로 중복을 막음).
 */
public class OutboxRelay {

  // 실패 횟수 (커밋 직후 발행 포함). 이 횟수째 실패하면 FAILED
  static final int MAX_ATTEMPTS = 5;
  // Kafka 응답을 기다리는 최대 시간
  static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);
  static final String TRACEPARENT_HEADER = "traceparent";
  private static final int ERROR_MAX_LENGTH = 500;
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final OutboxEventRepository repository;
  private final TransactionTemplate newTransaction;
  private final Executor executor;
  private final Clock clock;

  OutboxRelay(KafkaTemplate<String, String> kafkaTemplate, OutboxEventRepository repository,
              PlatformTransactionManager transactionManager, Executor executor, Clock clock) {
    this.kafkaTemplate = kafkaTemplate;
    this.repository = repository;
    this.executor = executor;
    this.clock = clock;
    // 커밋이 끝난 뒤에 실행되므로 반드시 새 트랜잭션 (기존 트랜잭션에 합류하면 UPDATE가 커밋되지 않음)
    this.newTransaction = new TransactionTemplate(transactionManager);
    this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  // 커밋 직후: 요청 스레드를 붙잡지 않도록 다른 스레드에서 보내고, 결과는 새 트랜잭션에 기록
  void publishAfterCommit(OutboxEvent event) {
    executor.execute(() -> {
      try {
        send(event);
        newTransaction.executeWithoutResult(status -> repository.markPublished(event.getId(), clock.instant()));
      } catch (Exception e) {
        String error = describe(e);
        newTransaction.executeWithoutResult(status -> repository.recordFailure(event.getId(), error, MAX_ATTEMPTS));
        log.warn("Outbox 커밋 직후 발행 실패, 재전송 대기: eventId={}, topic={}, error={}",
            event.getId(), event.getTopic(), error);
      }
    });
  }

  // Kafka가 저장했다고 응답할 때까지 기다림 (최대 SEND_TIMEOUT). payload는 로그에 남기지 않음
  void send(OutboxEvent event) throws Exception {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(event.getTopic(), event.getAggregateId(), event.getPayload());
    if (event.getTraceparent() != null) {
      record.headers().add(TRACEPARENT_HEADER, event.getTraceparent().getBytes(StandardCharsets.UTF_8));
    }
    try {
      kafkaTemplate.send(record).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw e;
    }
  }

  // 실패 원인을 짧게 (예외 이름 + 메시지, 최대 500자)
  static String describe(Exception e) {
    Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
    String text = cause.getClass().getSimpleName() + ": " + cause.getMessage();
    return text.length() <= ERROR_MAX_LENGTH ? text : text.substring(0, ERROR_MAX_LENGTH);
  }

  // 앱이 꺼질 때 Spring이 부름 (@Bean의 close() 자동 인식): 발행 스레드와 Kafka 연결 정리
  public void close() {
    if (executor instanceof ExecutorService service) {
      service.close();
    }
    if (kafkaTemplate.getProducerFactory() instanceof DisposableBean factory) {
      try {
        factory.destroy();
      } catch (Exception e) {
        log.warn("Outbox Kafka 연결 정리 실패: error={}", describe(e));
      }
    }
  }
}
