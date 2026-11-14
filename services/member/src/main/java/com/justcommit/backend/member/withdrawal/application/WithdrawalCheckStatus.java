package com.justcommit.backend.member.withdrawal.application;

// 탈퇴 가능 여부 확인 결과 (탈퇴 진행은 ALLOWED일 때만)
public enum WithdrawalCheckStatus {
  ALLOWED, // 세 서비스 모두 정상 응답 + 차단 없음
  BLOCKED, // 세 서비스 모두 정상 응답 + 한 곳 이상 차단
  UNAVAILABLE // 한 곳이라도 확인 실패 (차단과 함께 일어나도 이 상태가 우선)
}
