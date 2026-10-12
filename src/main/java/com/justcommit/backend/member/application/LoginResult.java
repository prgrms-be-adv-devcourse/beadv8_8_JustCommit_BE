package com.justcommit.backend.member.application;

import java.time.Duration;

// 로그인 결과 (Access는 응답 Body, Refresh는 컨트롤러에서 쿠키로 내려줌)
public record LoginResult(
        String accessToken,
        long expiresIn, // Access 토큰 유효 시간(초)
        String nickname,
        String role,
        String refreshToken,
        Duration refreshTokenTtl // 쿠키 Max-Age와 Redis TTL을 같은 값으로 맞추기 위해 전달
) {
  // 로그에 토큰 원문x
  public String toString() {
    return "LoginResult[expiresIn=" + expiresIn + ", nickname=" + nickname + ", role=" + role + "]";
  }
}
