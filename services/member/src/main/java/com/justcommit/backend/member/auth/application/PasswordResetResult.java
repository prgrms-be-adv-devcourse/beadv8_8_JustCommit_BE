package com.justcommit.backend.member.auth.application;

// 비밀번호 재설정 결과 (#412). revocationCompleted: 서버의 토큰 폐기 기록 완료 여부 (false = 재처리 대기)
public record PasswordResetResult(boolean revocationCompleted) {
}
