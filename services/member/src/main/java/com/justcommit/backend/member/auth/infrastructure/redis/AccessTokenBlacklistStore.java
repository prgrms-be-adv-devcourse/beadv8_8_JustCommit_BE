package com.justcommit.backend.member.auth.infrastructure.redis;

import com.justcommit.backend.common.security.TokenHash;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 로그아웃한 Access 토큰 거절 목록 (member가 쓰고 Gateway가 읽음).
 * 키는 Gateway TokenRevocationChecker와 같은 형식: auth:blacklist:{TokenHash.sha256Hex(토큰)}.
 * Gateway는 값이 아니라 키가 있는지만 본다. 토큰 원문은 저장하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AccessTokenBlacklistStore {

  private static final String BLACKLIST_KEY = "auth:blacklist:%s";
  private static final String BLACKLISTED = "1";

  private final StringRedisTemplate redisTemplate;

  /**
   * 검증을 통과한 Access 토큰을 남은 수명 동안 거절 목록에 넣는다.
   * TTL은 저장 직전 시각부터 exp까지로 계산한다. 남은 시간이 0 이하면 이미 만료라 저장하지 않는다.
   * Redis 오류는 그대로 올려 보낸다 (성공으로 숨기지 않음).
   *
   * @param accessToken "Bearer "를 뺀 토큰
   * @param expiresAt   검증한 토큰의 exp
   * @return 저장했으면 true
   */
  public boolean add(String accessToken, Instant expiresAt) {
    Duration remaining = Duration.between(Instant.now(), expiresAt);
    if (remaining.isZero() || remaining.isNegative()) {
      return false;
    }
    redisTemplate.opsForValue().set(String.format(BLACKLIST_KEY, TokenHash.sha256Hex(accessToken)), BLACKLISTED, remaining);
    return true;
  }
}
