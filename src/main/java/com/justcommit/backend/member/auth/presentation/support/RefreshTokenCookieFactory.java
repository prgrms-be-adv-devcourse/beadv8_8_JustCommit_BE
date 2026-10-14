package com.justcommit.backend.member.auth.presentation.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

// Refresh 토큰 쿠키 (일반 로그인·재발급·로그아웃·소셜 로그인 공통)
@Component
public class RefreshTokenCookieFactory {

  public static final String COOKIE_NAME = "refreshToken";

  // 재발급·로그아웃(/api/v1/auth/...) 요청에만 쿠키가 전송되도록 경로 제한
  private static final String COOKIE_PATH = "/api/v1/auth";

  // 로컬·HTTP 배포는 false, HTTPS 적용 시 COOKIE_SECURE=true
  private final boolean secure;

  public RefreshTokenCookieFactory(@Value("${custom.cookie.secure}") boolean secure) {
    this.secure = secure;
  }

  // 로그인·재발급: Redis TTL과 같은 Max-Age로 발급
  public ResponseCookie create(String refreshToken, Duration maxAge) {
    return ResponseCookie.from(COOKIE_NAME, refreshToken)
            .httpOnly(true)
            .secure(secure)
            .path(COOKIE_PATH) // 삭제할 때도 발급 때와 같은 Path여야 브라우저가 지움
            .maxAge(maxAge)
            .sameSite("Lax")
            .build();
  }

  public ResponseCookie expire() {
    return create("", Duration.ZERO);
  }
}
