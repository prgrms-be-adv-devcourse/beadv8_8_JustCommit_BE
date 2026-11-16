package com.justcommit.backend.member.auth.presentation.dto;

import com.justcommit.backend.member.auth.application.PasswordResetResult;

// 비밀번호 재설정 응답 (#412). revocationCompleted: 기존 로그인(토큰) 폐기 기록 완료 여부 (false = 재처리 대기)
public record PasswordResetResponse(boolean revocationCompleted) {

  public static PasswordResetResponse from(PasswordResetResult result) {
    return new PasswordResetResponse(result.revocationCompleted());
  }
}
