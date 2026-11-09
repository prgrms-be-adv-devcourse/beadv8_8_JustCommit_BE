package com.justcommit.backend.gateway.auth;

import com.justcommit.backend.common.security.TokenHash;
import com.justcommit.backend.common.security.VerifiedAccessToken;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 폐기된 토큰인지 Redis에서 확인한다 (키 원본: docs/reference/redis-keys.md). 키는 member가 쓰고 Gateway는 읽기만 한다.
 * - 로그아웃: `auth:blacklist:{토큰 해시}` 가 있으면 폐기
 * - 탈퇴 · 비밀번호 변경: `auth:revoked-member:{memberId}` = 끊은 시각(초), `iat <= 끊은 시각`이면 폐기
 * Redis를 못 읽으면 에러를 그대로 내보낸다 → 필터가 503 (fail-closed: 확인 못 한 토큰은 통과시키지 않음)
 */
@Component
public class TokenRevocationChecker {

  private static final String BLACKLIST_KEY = "auth:blacklist:";
  private static final String REVOKED_MEMBER_KEY = "auth:revoked-member:";

  private final ReactiveStringRedisTemplate redis;

  public TokenRevocationChecker(ReactiveStringRedisTemplate redis) {
    this.redis = redis;
  }

  public Mono<Boolean> isRevoked(String token, VerifiedAccessToken verified) {
    Mono<Boolean> blacklisted = redis.hasKey(BLACKLIST_KEY + TokenHash.sha256Hex(token));
    Mono<Boolean> memberRevoked = redis.opsForValue()
        .get(REVOKED_MEMBER_KEY + verified.member().memberId())
        .map(revokedAt -> verified.issuedAt() <= Long.parseLong(revokedAt))
        .defaultIfEmpty(false);
    // 두 키를 동시에 조회
    return Mono.zip(blacklisted, memberRevoked, (a, b) -> a || b);
  }
}
