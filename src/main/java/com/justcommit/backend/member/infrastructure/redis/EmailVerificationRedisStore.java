package com.justcommit.backend.member.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

// 이메일 인증 관련 Redis 키 관리(키규칙 도메인:용도:식별자)
@Component
@RequiredArgsConstructor
public class EmailVerificationRedisStore {

  // 이메일 인증 번호(5분), 인증 완료(30분), 재발송 제한(60초)
  private static final String CODE_KEY = "auth:email:%s";
  private static final String VERIFIED_KEY = "auth:email:verified:%s";
  private static final String COOLDOWN_KEY = "auth:email:cooldown:%s";

  private final StringRedisTemplate redisTemplate;

  // ===== 재발송 제한 =====
  // 키 없을 때만 저장(SET NX EX), 저장 성공 = 발송 가능, 실패 = 제한
  public boolean tryStartCooldown(String email, Duration ttl) {
    Boolean saved = redisTemplate.opsForValue().setIfAbsent(key(COOLDOWN_KEY, email), "1", ttl);
    return Boolean.TRUE.equals(saved);
  }

  // 재발송까지 남은 시간, 만료됐으면 1초로 응답
  public long getCooldownRemainingSeconds(String email) {
    Long ttl = redisTemplate.getExpire(key(COOLDOWN_KEY, email), TimeUnit.SECONDS);
    return (ttl == null || ttl < 1) ? 1 : ttl;
  }

  public void deleteCooldown(String email) {
    redisTemplate.delete(key(COOLDOWN_KEY, email));
  }

  // ===== 인증번호 =====
  // 재발송하면 같은 키에 덮어써서 이전 번호는 자동으로 무효
  public void saveCode(String email, String code, Duration ttl) {
    redisTemplate.opsForValue().set(key(CODE_KEY, email), code, ttl);
  }

  public Optional<String> findCode(String email) {
    return Optional.ofNullable(redisTemplate.opsForValue().get(key(CODE_KEY, email)));
  }

  public void deleteCode(String email) {
    redisTemplate.delete(key(CODE_KEY, email));
  }

  // ===== 인증 완료 (회원가입에서 확인,삭제) =====
  public void saveVerified(String email, Duration ttl) {
    redisTemplate.opsForValue().set(key(VERIFIED_KEY, email), "true", ttl);
  }

  public boolean isVerified(String email) {
    return Boolean.TRUE.equals(redisTemplate.hasKey(key(VERIFIED_KEY, email)));
  }

  public void deleteVerified(String email) {
    redisTemplate.delete(key(VERIFIED_KEY, email));
  }

  private String key(String pattern, String email) {
    return String.format(pattern, email);
  }
}
