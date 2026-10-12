package com.justcommit.backend.member.application;

import java.time.Duration;

// 토큰 재발급 결과(Access는 응답 Body, refresh는 쿠키로 전달)
public record TokenReissueResult(
        String accessToken,
        long expiresIn,
        String refreshToken,
        Duration refreshTokenTtl
) {
  // 로그에 토큰 원문x
  @Override
  public String toString() {
    return "TokenReissueResult[accessToken=***, expiresIn=" + expiresIn
            + ", refreshToken=***, refreshTokenTtl=" + refreshTokenTtl + "]";
  }
}
