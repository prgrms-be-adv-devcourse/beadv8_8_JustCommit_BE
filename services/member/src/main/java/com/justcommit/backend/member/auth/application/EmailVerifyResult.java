package com.justcommit.backend.member.auth.application;

import java.time.Duration;

// 인증번호 확인 결과 (#412). 비밀번호 재설정 목적일 때만 일회용 재설정 토큰 · 남은 시간(초)이 있다 (가입은 비어 있음)
public record EmailVerifyResult(String resetToken, long resetTokenExpiresIn) {

  public static EmailVerifyResult none() {
    return new EmailVerifyResult(null, 0);
  }

  public static EmailVerifyResult resetToken(String token, Duration ttl) {
    return new EmailVerifyResult(token, ttl.toSeconds());
  }
}
