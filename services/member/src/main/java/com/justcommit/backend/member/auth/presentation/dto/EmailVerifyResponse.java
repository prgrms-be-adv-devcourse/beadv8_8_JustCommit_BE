package com.justcommit.backend.member.auth.presentation.dto;

import com.justcommit.backend.member.auth.application.EmailVerifyResult;

// 인증번호 확인 응답 (#412). 비밀번호 재설정 목적일 때만 일회용 재설정 토큰 · 남은 시간(초)
// 가입은 null을 돌려줘 응답의 data가 빠진다 (공통 응답이 null 필드를 내보내지 않음 → 기존 가입 응답과 같음)
public record EmailVerifyResponse(String resetToken, long expiresIn) {

  public static EmailVerifyResponse from(EmailVerifyResult result) {
    if (result.resetToken() == null) {
      return null;
    }
    return new EmailVerifyResponse(result.resetToken(), result.resetTokenExpiresIn());
  }
}
