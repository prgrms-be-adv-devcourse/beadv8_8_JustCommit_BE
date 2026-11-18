package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.security.TokenException;
import com.justcommit.backend.member.auth.infrastructure.jwt.JwtProvider;
import com.justcommit.backend.member.auth.infrastructure.redis.AccessTokenBlacklistStore;
import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenRedisStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

// 로그아웃: 이 기기의 Refresh 토큰 폐기 + 함께 온 Access 토큰을 거절 목록(블랙리스트)에 등록
@Slf4j
@Service
@RequiredArgsConstructor
public class LogoutService {

  private final RefreshTokenRedisStore refreshTokenStore;
  private final AccessTokenBlacklistStore accessTokenBlacklistStore;
  private final JwtProvider jwtProvider;

  // 두 토큰 모두 선택. 없거나 유효하지 않아도 예외 없이 끝남 (토큰 오류만 무시)
  // Refresh 삭제는 Access 검증 결과와 상관없이 진행
  // Redis 오류는 잡지 않음 → 호출한 쪽 500 (일부만 처리될 수 있어 클라이언트는 실패로 보고 재시도)
  public void logout(String accessToken, String refreshToken) {
    if (refreshToken != null && !refreshToken.isBlank()) {
      refreshTokenStore.delete(refreshToken);
    }
    if (accessToken != null && !accessToken.isBlank()) {
      addToBlacklist(accessToken);
    }
  }

  // 검증(서명 · 만료 · ACCESS 타입)을 통과한 토큰만 저장. TTL은 저장소가 저장 직전에 계산
  private void addToBlacklist(String accessToken) {
    Instant expiresAt;
    try {
      expiresAt = jwtProvider.getVerifiedAccessTokenExpiresAt(accessToken);
    } catch (TokenException e) {
      // 만료 · 잘못된 토큰: 저장만 건너뜀 (토큰 원문은 로그에 남기지 않음)
      log.debug("로그아웃 Access 토큰을 블랙리스트에 넣지 않음: reason={}", e.getReason());
      return;
    }
    accessTokenBlacklistStore.add(accessToken, expiresAt);
  }
}
