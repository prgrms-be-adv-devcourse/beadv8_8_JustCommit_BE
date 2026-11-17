package com.justcommit.backend.member.auth.infrastructure.redis;

import com.justcommit.backend.common.security.TokenHash;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 비밀번호 재설정 토큰 (#412). Refresh 토큰처럼 원문은 저장하지 않고 SHA-256 해시를 키로 쓴다.
 * 값은 "{회원 id}:{발급 당시 인증 버전}". 재설정 때 인증 버전을 다시 비교해, 토큰을 받은 뒤
 * 비밀번호가 바뀌었다면(변경 · 다른 토큰으로 재설정) 남은 토큰을 쓸 수 없게 한다.
 */
@Component
@RequiredArgsConstructor
public class PasswordResetTokenStore {

  private static final String KEY = "auth:password-reset:%s";

  private final StringRedisTemplate redisTemplate;

  public record ResetTokenOwner(Long memberId, int tokenVersion) {
  }

  public void save(String token, Long memberId, int tokenVersion, Duration ttl) {
    redisTemplate.opsForValue().set(key(token), memberId + ":" + tokenVersion, ttl);
  }

  // 꺼내면서 지운다 (GETDEL): 같은 토큰은 한 번만 쓸 수 있다. 없거나 값이 깨졌으면 빈 값
  public Optional<ResetTokenOwner> take(String token) {
    String value = redisTemplate.opsForValue().getAndDelete(key(token));
    if (value == null) {
      return Optional.empty();
    }
    int separator = value.indexOf(':');
    if (separator < 0) {
      return Optional.empty();
    }
    try {
      return Optional.of(new ResetTokenOwner(
          Long.parseLong(value.substring(0, separator)), Integer.parseInt(value.substring(separator + 1))));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private String key(String token) {
    return String.format(KEY, TokenHash.sha256Hex(token));
  }
}
