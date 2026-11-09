package com.justcommit.backend.common.messaging.outbox;

public enum OutboxStatus {
  // 아직 Kafka가 받았다고 확인하지 못함 (재전송 대상)
  PENDING,
  // Kafka가 저장했다고 응답함
  PUBLISHED,
  // 정해진 횟수만큼 실패해 재전송을 멈춤 (사람이 확인)
  FAILED
}