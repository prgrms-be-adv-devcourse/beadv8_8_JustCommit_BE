package com.justcommit.backend.member.withdrawal.application;

// 탈퇴 결과. revocationCompleted: 서버의 토큰 폐기 기록 완료 여부 (false = 커밋은 됐고 폐기 기록만 재처리 대기)
public record WithdrawalResult(boolean revocationCompleted) {
}
