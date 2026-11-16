package com.justcommit.backend.member.auth.infrastructure.redis;

import com.justcommit.backend.member.auth.domain.EmailPurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 이메일 인증 Redis 키 관리 (키 규칙 도메인:용도:식별자)
 * - 목적별 (#412): 인증번호 · 틀린 횟수 · 인증 완료. 다른 목적으로 받은 인증을 쓰지 못하게 목적을 키에 넣는다
 * - 이메일 단위: 재발송 제한(60초) · 하루 발송 횟수. 목적과 관계없이 같은 이메일로 보내는 메일 수를 함께 센다
 * - 판단과 변경이 함께 필요한 곳(발송 자리 확보 · 인증번호 확인)은 Lua 한 번으로 처리해,
 *   동시 요청에도 하루 한도를 넘지 않고 같은 인증번호가 두 번 승인되지 않게 한다
 */
@Component
@RequiredArgsConstructor
public class EmailVerificationRedisStore {

  private static final String CODE_KEY = "auth:email:%s:%s";
  private static final String ATTEMPTS_KEY = "auth:email:attempts:%s:%s";
  private static final String VERIFIED_KEY = "auth:email:verified:%s:%s";
  private static final String COOLDOWN_KEY = "auth:email:cooldown:%s";
  private static final String DAILY_KEY = "auth:email:daily:%s";

  // KEYS[1] = 재발송 제한 키, KEYS[2] = 하루 발송 키 / ARGV[1] = 재발송 간격(초), ARGV[2] = 하루 한도, ARGV[3] = 하루 기간(초)
  // 반환 {상태, 남은 초}: 0 = 확보, 1 = 재발송 제한, 2 = 하루 한도 초과. 하루 기간은 첫 발송부터 센다
  @SuppressWarnings("rawtypes")
  private static final RedisScript<List> ACQUIRE_SEND_SCRIPT = new DefaultRedisScript<>("""
      local cooldown = redis.call('TTL', KEYS[1])
      if cooldown > 0 then
        return {1, cooldown}
      end
      local count = tonumber(redis.call('GET', KEYS[2]) or '0')
      if count >= tonumber(ARGV[2]) then
        return {2, redis.call('TTL', KEYS[2])}
      end
      redis.call('INCR', KEYS[2])
      if redis.call('TTL', KEYS[2]) < 0 then
        redis.call('EXPIRE', KEYS[2], ARGV[3])
      end
      redis.call('SET', KEYS[1], '1', 'EX', ARGV[1])
      return {0, 0}
      """, List.class);

  // KEYS[1] = 재발송 제한 키, KEYS[2] = 하루 발송 키. 확보한 발송 자리를 되돌린다 (재발송 제한 해제 · 하루 횟수 -1)
  private static final RedisScript<Long> RELEASE_SEND_SCRIPT = new DefaultRedisScript<>("""
      redis.call('DEL', KEYS[1])
      local count = tonumber(redis.call('GET', KEYS[2]) or '0')
      if count > 0 then
        redis.call('DECR', KEYS[2])
      end
      return 1
      """, Long.class);

  // KEYS[1] = 인증번호 키, KEYS[2] = 틀린 횟수 키 / ARGV[1] = 입력한 번호, ARGV[2] = 최대 틀린 횟수, ARGV[3] = 틀린 횟수 TTL(초)
  // 반환: 0 = 없음(만료), 1 = 일치(번호 · 횟수 삭제), 2 = 불일치, 3 = 최대 횟수만큼 틀림(번호 · 횟수 삭제)
  private static final RedisScript<Long> VERIFY_SCRIPT = new DefaultRedisScript<>("""
      local saved = redis.call('GET', KEYS[1])
      if not saved then
        return 0
      end
      if saved == ARGV[1] then
        redis.call('DEL', KEYS[1], KEYS[2])
        return 1
      end
      local wrong = redis.call('INCR', KEYS[2])
      if redis.call('TTL', KEYS[2]) < 0 then
        redis.call('EXPIRE', KEYS[2], ARGV[3])
      end
      if wrong >= tonumber(ARGV[2]) then
        redis.call('DEL', KEYS[1], KEYS[2])
        return 3
      end
      return 2
      """, Long.class);

  private final StringRedisTemplate redisTemplate;

  public enum SendStatus { ACQUIRED, COOLDOWN, DAILY_LIMIT }

  // retryAfterSeconds: 다시 보낼 수 있을 때까지 남은 초 (ACQUIRED면 0)
  public record SendResult(SendStatus status, long retryAfterSeconds) {
  }

  public enum VerifyResult { MATCHED, MISMATCH, EXHAUSTED, EXPIRED }

  // ===== 발송 자리 (이메일 단위) =====

  // 재발송 제한 · 하루 한도를 확인하고, 보낼 수 있으면 하루 횟수 +1 · 재발송 제한 시작까지 한 번에 처리
  public SendResult tryAcquireSend(String email, Duration cooldown, int dailyLimit, Duration dailyWindow) {
    List<?> result = redisTemplate.execute(ACQUIRE_SEND_SCRIPT,
        List.of(key(COOLDOWN_KEY, email), key(DAILY_KEY, email)),
        String.valueOf(cooldown.toSeconds()), String.valueOf(dailyLimit), String.valueOf(dailyWindow.toSeconds()));
    int status = ((Number) result.get(0)).intValue();
    long remaining = Math.max(1, ((Number) result.get(1)).longValue()); // 0초 · 음수는 1초로 응답
    return switch (status) {
      case 0 -> new SendResult(SendStatus.ACQUIRED, 0);
      case 1 -> new SendResult(SendStatus.COOLDOWN, remaining);
      default -> new SendResult(SendStatus.DAILY_LIMIT, remaining);
    };
  }

  // 메일 발송이 실패하면 확보한 자리를 되돌린다 (서버 쪽 실패는 하루 횟수에 넣지 않음)
  public void releaseSend(String email) {
    redisTemplate.execute(RELEASE_SEND_SCRIPT, List.of(key(COOLDOWN_KEY, email), key(DAILY_KEY, email)));
  }

  // ===== 인증번호 (목적별) =====

  // 재발송하면 같은 키에 덮어써 이전 번호는 자동으로 무효, 틀린 횟수도 새로 시작
  public void saveCode(EmailPurpose purpose, String email, String code, Duration ttl) {
    redisTemplate.opsForValue().set(key(CODE_KEY, purpose, email), code, ttl);
    redisTemplate.delete(key(ATTEMPTS_KEY, purpose, email));
  }

  public void deleteCode(EmailPurpose purpose, String email) {
    redisTemplate.delete(List.of(key(CODE_KEY, purpose, email), key(ATTEMPTS_KEY, purpose, email)));
  }

  // 비교 · 틀린 횟수 증가 · 폐기를 한 번에 처리 (같은 번호가 동시에 두 번 승인되지 않음)
  public VerifyResult verifyCode(EmailPurpose purpose, String email, String code,
                                 int maxWrongAttempts, Duration attemptsTtl) {
    Long result = redisTemplate.execute(VERIFY_SCRIPT,
        List.of(key(CODE_KEY, purpose, email), key(ATTEMPTS_KEY, purpose, email)),
        code, String.valueOf(maxWrongAttempts), String.valueOf(attemptsTtl.toSeconds()));
    return switch (result == null ? 0 : result.intValue()) {
      case 1 -> VerifyResult.MATCHED;
      case 2 -> VerifyResult.MISMATCH;
      case 3 -> VerifyResult.EXHAUSTED;
      default -> VerifyResult.EXPIRED;
    };
  }

  // ===== 인증 완료 (목적별, 가입은 SIGNUP만 확인 · 삭제) =====

  public void saveVerified(EmailPurpose purpose, String email, Duration ttl) {
    redisTemplate.opsForValue().set(key(VERIFIED_KEY, purpose, email), "true", ttl);
  }

  public boolean isVerified(EmailPurpose purpose, String email) {
    return Boolean.TRUE.equals(redisTemplate.hasKey(key(VERIFIED_KEY, purpose, email)));
  }

  public void deleteVerified(EmailPurpose purpose, String email) {
    redisTemplate.delete(key(VERIFIED_KEY, purpose, email));
  }

  private String key(String pattern, String email) {
    return String.format(pattern, email);
  }

  private String key(String pattern, EmailPurpose purpose, String email) {
    return String.format(pattern, purpose.getKeySegment(), email);
  }
}
