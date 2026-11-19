package com.justcommit.backend.common.messaging.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox 알림용 지표 (#301, docs/infra/observability.md 「Outbox 대기」).
 * 수집할 때마다 DB를 읽지 않도록 재전송 스케줄러가 10초마다 갱신한 값을 내보낸다.
 * 파드가 여러 개면 같은 값을 내므로 알림은 max()로 건다.
 */
public class OutboxMetrics {

  // Prometheus: outbox_pending_oldest_age_seconds — 가장 오래된 PENDING 행이 쌓인 지 몇 초 (없으면 0)
  static final String PENDING_OLDEST_AGE = "outbox.pending.oldest.age";
  // Prometheus: outbox_failed — 재전송을 포기한(FAILED) 행 수
  static final String FAILED = "outbox.failed";
  private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

  private final OutboxEventRepository repository;
  private final Clock clock;
  private final AtomicLong pendingOldestAgeSeconds = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();

  OutboxMetrics(OutboxEventRepository repository, MeterRegistry registry, Clock clock) {
    this.repository = repository;
    this.clock = clock;
    Gauge.builder(PENDING_OLDEST_AGE, pendingOldestAgeSeconds, AtomicLong::get)
        .baseUnit("seconds")
        .description("가장 오래된 미발행(PENDING) Outbox 행의 나이")
        .register(registry);
    Gauge.builder(FAILED, failed, AtomicLong::get)
        .description("재전송을 포기한(FAILED) Outbox 행 수")
        .register(registry);
  }

  // 실패해도 이전 값을 유지하고 다음 주기에 다시 센다. 지표 때문에 재전송이 멈추지 않게 예외를 밖으로 내지 않는다
  void refresh() {
    try {
      pendingOldestAgeSeconds.set(repository.findOldestPendingCreatedAt()
          .map(createdAt -> Math.max(0, Duration.between(createdAt, clock.instant()).toSeconds()))
          .orElse(0L));
      failed.set(repository.countByStatus(OutboxStatus.FAILED));
    } catch (RuntimeException e) {
      log.warn("Outbox 지표 갱신 실패, 이전 값 유지: error={}", OutboxRelay.describe(e));
    }
  }
}
