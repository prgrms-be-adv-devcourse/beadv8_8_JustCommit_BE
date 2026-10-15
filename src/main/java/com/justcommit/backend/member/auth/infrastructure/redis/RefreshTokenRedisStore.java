package com.justcommit.backend.member.auth.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

// Refresh 토큰 Redis 관리 (원문은 저장X, SHA-256 해시를 키로 사용)
@Component
@RequiredArgsConstructor
public class RefreshTokenRedisStore {

  // 키: auth:refresh:{토큰 해시}, 값: 회원 ID
  private static final String REFRESH_KEY = "auth:refresh:%s";

  private final StringRedisTemplate redisTemplate;

  // 로그인 시 저장
  public void save(String refreshToken, Long memberId, Duration ttl) {
    redisTemplate.opsForValue().set(key(refreshToken), String.valueOf(memberId), ttl);
  }

  // 재발급 시 조회와 삭제를 한 번에 처리 (GETDEL)
  // 따로 조회 → 삭제하면 동시에 들어온 두 요청이 둘 다 통과할 수 있음 → 원자 처리로 한 번만 성공
  public Optional<Long> findAndDelete(String refreshToken) {
    String memberId = redisTemplate.opsForValue().getAndDelete(key(refreshToken));
    return Optional.ofNullable(memberId).map(Long::valueOf);
  }

  // 로그아웃 시 삭제
  public void delete(String refreshToken) {
    redisTemplate.delete(key(refreshToken));
  }

  private String key(String refreshToken) {
    return String.format(REFRESH_KEY, sha256(refreshToken));
  }

  // 토큰 원문 → SHA-256 16진수 문자열(64자)
  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256은 모든 자바 실행 환경에 반드시 있는 알고리즘이라 실제로는 발생x
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
    }
  }
}
