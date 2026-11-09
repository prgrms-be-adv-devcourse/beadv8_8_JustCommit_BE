package com.justcommit.backend.common.messaging.outbox;

import com.justcommit.backend.common.messaging.EventEnvelope;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 업무 데이터와 같은 트랜잭션에 저장한 뒤 Kafka로 보내는 이벤트 (docs/conventions/events.md 「Outbox」).
 * 테이블은 각 서비스가 자기 Flyway로 만든다 (공통 모듈은 권장 SQL만 제공).
 */
@Entity
@Table(name = "outbox_event",
    indexes = @Index(name = "idx_outbox_event_status_created_at", columnList = "status, created_at"))
public class OutboxEvent {

  // 봉투의 eventId와 같은 값
  @Id
  private UUID id;

  // Kafka Key
  @Column(name = "aggregate_id", nullable = false, length = 100)
  private String aggregateId;

  @Column(nullable = false, length = 100)
  private String topic;

  @Column(name = "event_type", nullable = false, length = 100)
  private String eventType;

  // 봉투 JSON 그대로
  @Column(nullable = false, columnDefinition = "text")
  private String payload;

  // 추적 설정(common-logging) 전까지는 비어 있음
  @Column(length = 55)
  private String traceparent;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private OutboxStatus status;

  @Column(name = "published_at")
  private Instant publishedAt;

  // 실패한 발행 횟수 (커밋 직후 발행 포함)
  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "last_error", length = 500)
  private String lastError;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  // JPA가 객체를 만들 때만 씀
  protected OutboxEvent() {
  }

  private OutboxEvent(EventEnvelope envelope, String topic, String payload, String traceparent, Instant now) {
    this.id = UUID.fromString(envelope.eventId());
    this.aggregateId = envelope.aggregateId();
    this.topic = topic;
    this.eventType = envelope.eventType();
    this.payload = payload;
    this.traceparent = traceparent;
    this.status = OutboxStatus.PENDING;
    this.attemptCount = 0;
    this.createdAt = now;
  }

  // 새 이벤트는 항상 PENDING으로 시작
  public static OutboxEvent pending(EventEnvelope envelope, String topic, String payload,
                                    String traceparent, Instant now) {
    return new OutboxEvent(envelope, topic, payload, traceparent, now);
  }

  public UUID getId() {
    return id;
  }

  public String getAggregateId() {
    return aggregateId;
  }

  public String getTopic() {
    return topic;
  }

  public String getEventType() {
    return eventType;
  }

  public String getPayload() {
    return payload;
  }

  public String getTraceparent() {
    return traceparent;
  }

  public OutboxStatus getStatus() {
    return status;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public String getLastError() {
    return lastError;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
