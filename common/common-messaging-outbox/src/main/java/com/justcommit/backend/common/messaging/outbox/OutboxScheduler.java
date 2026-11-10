package com.justcommit.backend.common.messaging.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 재전송 · 정리 (docs/conventions/events.md 「Outbox」, 모든 서비스 같은 값).
 * 파드가 여러 개여도 같은 행을 두 파드가 동시에 보내지 않게 한 행씩 FOR UPDATE SKIP LOCKED (정책 1.4-1).
 */
public class OutboxScheduler {

  // 커밋 직후 발행이 진행 중인 행과 겹칠 가능성을 줄이는 여유 (중복을 완전히 막지는 않음, 받는 쪽이 업무 키로 막음)
  static final Duration RETRY_GRACE = Duration.ofSeconds(5);
  static final int RETRY_BATCH_SIZE = 100;
  static final Duration RETENTION = Duration.ofDays(7);
  private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

  private final OutboxEventRepository repository;
  private final OutboxRelay relay;
  private final TransactionTemplate transaction;
  private final Clock clock;

  OutboxScheduler(OutboxEventRepository repository, OutboxRelay relay,
                  PlatformTransactionManager transactionManager, Clock clock) {
    this.repository = repository;
    this.relay = relay;
    this.transaction = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  // 10초마다: 앞 실행이 끝난 뒤 10초 쉬고 다시 (한 파드 안에서 겹쳐 돌지 않음)
  @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.SECONDS)
  public void retry() {
    List<UUID> ids = repository.findRetryIds(clock.instant().minus(RETRY_GRACE), Limit.of(RETRY_BATCH_SIZE));
    for (UUID id : ids) {
      try {
        // 한 행씩 짧은 트랜잭션: 잠금은 이 행을 보내는 동안(최대 5초)만 유지
        transaction.executeWithoutResult(status -> retryOne(id));
      } catch (RuntimeException e) {
        // DB 오류 등으로 이 행을 기록하지 못해도 다음 행은 계속 (이 행은 다음 주기에 다시)
        log.warn("Outbox 재전송 처리 실패: eventId={}, error={}", id, OutboxRelay.describe(e));
      }
    }
  }

  // 1시간마다: 발행이 끝난 지 7일 지난 행 삭제 (서버가 09~18시에만 켜져 있어 시각 지정 대신 주기로)
  @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
  public void cleanUp() {
    int deleted = transaction.execute(status ->
        repository.deletePublishedBefore(clock.instant().minus(RETENTION)));
    if (deleted > 0) {
      log.info("Outbox 정리: deleted={}", deleted);
    }
  }

  private void retryOne(UUID id) {
    // 다른 파드가 잡고 있거나 그사이 발행 · 포기된 행이면 빈 값 → 건너뜀
    Optional<OutboxEvent> locked = repository.findPendingForUpdateSkipLocked(id);
    if (locked.isEmpty()) {
      return;
    }
    OutboxEvent event = locked.get();
    try {
      relay.send(event);
      repository.markPublished(id, clock.instant());
    } catch (Exception e) {
      String error = OutboxRelay.describe(e);
      repository.recordFailure(id, error, OutboxRelay.MAX_ATTEMPTS);
      if (event.getAttemptCount() + 1 >= OutboxRelay.MAX_ATTEMPTS) {
        // 재전송을 멈춘 행: Grafana 알림 대상 (정책 1.2-3)
        log.warn("Outbox 발행 포기(FAILED): eventId={}, topic={}, attempts={}, error={}",
            id, event.getTopic(), OutboxRelay.MAX_ATTEMPTS, error);
      } else {
        log.warn("Outbox 재전송 실패: eventId={}, topic={}, attempts={}, error={}",
            id, event.getTopic(), event.getAttemptCount() + 1, error);
      }
    }
  }
}
