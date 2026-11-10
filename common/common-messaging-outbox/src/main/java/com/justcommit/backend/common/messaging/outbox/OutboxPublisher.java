package com.justcommit.backend.common.messaging.outbox;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.IntegrationEvent;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;

/**
 * 서비스가 이벤트를 보내는 입구 (Kafka로 직접 보내지 않는다, 정책 10.13-6).
 * 업무 트랜잭션 안에서 부르면 같은 트랜잭션에 Outbox 행을 저장하고, 커밋된 뒤에만 Kafka로 보낸다.
 * 롤백되면 행도 사라지고 발행도 하지 않는다.
 */
public class OutboxPublisher {
  
  private final EntityManager entityManager;
  private final EventEnvelopeMapper mapper;
  private final OutboxRelay relay;
  private final Clock clock;

  OutboxPublisher(EntityManager entityManager, EventEnvelopeMapper mapper, OutboxRelay relay, Clock clock) {
    this.entityManager = entityManager;
    this.mapper = mapper;
    this.relay = relay;
    this.clock = clock;
  }

  public void publish(IntegrationEvent event) {
    // 트랜잭션 밖에서 부르면 저장과 발행이 따로 놀아 유실, 유령 이벤트가 생김 -> 바로 막음
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Outbox 발행은 업무 트랜잭션(@Transactional) 안에서 해야 합니다: eventType=" + event.eventType());
    }

    EventEnvelope envelope = mapper.wrap(event);
    // TODO: common-logging 추적 설정 이후 현재 요청의 traceparent를 함께 저장
    OutboxEvent outboxEvent = OutboxEvent.pending(envelope, event.topic(), mapper.toJson(envelope), null, clock.instant());
    entityManager.persist(outboxEvent);

    // 커밋이 성공한 뒤에만 릴레이에 넘김 (롤백되면 호출되지 않음)
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        relay.publishAfterCommit(outboxEvent);
      }
    });
  }
}
