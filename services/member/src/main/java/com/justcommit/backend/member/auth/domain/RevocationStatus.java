package com.justcommit.backend.member.auth.domain;

// 폐기 작업 상태 (#409)
// PENDING: Redis 기록 대기 · RECORDED: Redis 기록 확인 · EXPIRED: 기록 전에 보관 기한이 지나 기록할 필요가 없어짐
public enum RevocationStatus {
  PENDING,
  RECORDED,
  EXPIRED
}
