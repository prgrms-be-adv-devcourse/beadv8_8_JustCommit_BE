package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenRedisStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

// Refresh 토큰 발급 (로그인·재발급 공통): 랜덤 문자열 생성 → Redis 저장
@Component
@RequiredArgsConstructor
public class RefreshTokenIssuer {

  public static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(1);

  private static final int REFRESH_TOKEN_BYTES = 32; // 256비트 랜덤
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final RefreshTokenRedisStore refreshTokenStore;

  // 새 Refresh 토큰을 회원 id와 함께 저장하고 원문 반환 (원문은 쿠키로만 전달, Redis에는 해시만)
  public String issue(Long memberId) {
    String refreshToken = newRefreshToken();
    refreshTokenStore.save(refreshToken, memberId, REFRESH_TOKEN_TTL);
    return refreshToken;
  }

  // 랜덤 문자열
  private String newRefreshToken() {
    byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
