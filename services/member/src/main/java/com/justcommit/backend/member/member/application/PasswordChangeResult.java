package com.justcommit.backend.member.member.application;

// 비밀번호 변경 결과. revocationCompleted: 서버의 토큰 폐기 기록 완료 여부 (false = 재처리 대기)
public record PasswordChangeResult(boolean revocationCompleted) {
}
