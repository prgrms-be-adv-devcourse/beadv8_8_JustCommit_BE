package com.justcommit.backend.member.withdrawal.infrastructure.redis;

import com.justcommit.backend.common.security.TokenHash;
import com.justcommit.backend.member.auth.domain.VerificationBinding;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 탈퇴 토큰 (#407). 계좌 변경 토큰(AccountChangeTokenStore)과 같은 방식으로, 원문 대신 SHA-256 해시를 키로 쓴다.
 * 값은 "{회원 id}:{인증번호 발송 당시 인증 버전}" (VerificationBinding 형식). 최종 탈퇴 때 회원 · 인증 버전을 다시 비교한다
 */
@Component
@RequiredArgsConstructor
public class WithdrawalTokenStore {

  private static final String KEY = "auth:withdrawal:%s";

  private final StringRedisTemplate redisTemplate;

  public void save(String token, VerificationBinding owner, Duration ttl) {
    redisTemplate.opsForValue().set(key(token), owner.toBinding(), ttl);
  }

  // 지우지 않고 읽는다 (최종 탈퇴의 재확인 단계). 없거나 값이 깨졌으면 빈 값
  public Optional<VerificationBinding> find(String token) {
    return VerificationBinding.fromBinding(redisTemplate.opsForValue().get(key(token)));
  }

  // 꺼내면서 지운다 (GETDEL, 카카오 연결 해제 직전): 같은 토큰은 한 번만 쓸 수 있다. 없거나 값이 깨졌으면 빈 값
  public Optional<VerificationBinding> take(String token) {
    return VerificationBinding.fromBinding(redisTemplate.opsForValue().getAndDelete(key(token)));
  }

  private String key(String token) {
    return String.format(KEY, TokenHash.sha256Hex(token));
  }
}
