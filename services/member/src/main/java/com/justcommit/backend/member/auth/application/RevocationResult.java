package com.justcommit.backend.member.auth.application;

// 토큰 폐기 결과 (#409). 변경 실패(롤백)는 예외, 커밋 결과 불명은 RevocationOutcomeUnknownException
public enum RevocationResult {
  // DB 커밋 + Redis 폐기 시각 기록 확인 (보관 기한이 이미 지나 기록이 필요 없는 경우 포함)
  COMPLETED,
  // DB 커밋은 됐지만 Redis 기록을 확인하지 못함 → 재처리 스케줄러가 다시 기록
  PENDING
}
