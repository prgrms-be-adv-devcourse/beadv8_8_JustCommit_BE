package com.justcommit.backend.member.account.infrastructure;

import com.justcommit.backend.common.security.TokenHash;
import com.justcommit.backend.member.auth.domain.VerificationBinding;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 계좌 변경 토큰 (#413). 재설정 토큰처럼 원문은 저장하지 않고 SHA-256 해시를 키로 쓴다.
 * 값은 "{회원 id}:{인증번호 발송 당시 인증 버전}" (VerificationBinding 형식). 계좌를 바꿀 때 회원 · 인증 버전을 다시 비교한다
 */
@Component
@RequiredArgsConstructor
public class AccountChangeTokenStore {

  private static final String KEY = "auth:account-change:%s";

  private final StringRedisTemplate redisTemplate;

  public void save(String token, VerificationBinding owner, Duration ttl) {
    redisTemplate.opsForValue().set(key(token), owner.toBinding(), ttl);
  }

  private String key(String token) {
    return String.format(KEY, TokenHash.sha256Hex(token));
  }
}
