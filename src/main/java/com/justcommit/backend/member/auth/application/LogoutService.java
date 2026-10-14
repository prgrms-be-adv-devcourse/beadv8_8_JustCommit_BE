package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.member.infrastructure.redis.RefreshTokenRedisStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 로그아웃(Refresh 토큰 폐기)
@Service
@RequiredArgsConstructor
public class LogoutService {

  private final RefreshTokenRedisStore refreshTokenStore;

  // 로그아웃: Refresh 토큰을 Redis에서 삭제 -> 이 토큰으로 재발급 불가
  // 쿠키가 없거나 이미 만료삭제된 토큰이어도 예외 없이 끝냄(로그아웃은 항상 성공)
  public void logout(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      return;
    }
    refreshTokenStore.delete(refreshToken);
  }
}
