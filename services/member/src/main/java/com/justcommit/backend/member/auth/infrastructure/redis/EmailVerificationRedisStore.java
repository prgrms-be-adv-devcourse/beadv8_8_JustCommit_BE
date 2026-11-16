package com.justcommit.backend.member.auth.infrastructure.redis;

import com.justcommit.backend.member.auth.domain.EmailPurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 이메일 인증 Redis 키 관리 (키 규칙 도메인:용도:식별자)
 * - 목적별 (#412): 인증번호 · 틀린 횟수 · 인증 완료. 다른 목적으로 받은 인증을 쓰지 못하게 목적을 키에 넣는다
 * - 이메일 단위: 재발송 제한(60초) · 하루 발송 횟수. 목적과 관계없이 같은 이메일로 보내는 메일 수를 함께 센다
 * - 판단과 변경이 함께 필요한 곳(발송 자리 확보 · 되돌리기 · 인증번호 확인)은 Lua 한 번으로 처리해,
 *   동시 요청에도 하루 한도를 넘지 않고 같은 인증번호가 두 번 승인되지 않게 한다
 * - 발송 요청마다 식별자를 만들어 재발송 제한 · 인증번호 값과 하루 발송 Set에 남긴다.
 *   인증번호는 재발송 제한의 주인(자리를 잡은 요청)만 저장하고, 메일 발송이 늦게 실패해도 그 사이 다른 요청이 만든
 *   인증번호 · 재발송 제한 · 새 하루 구간의 횟수는 건드리지 않고 자기 것만 되돌린다
 */
@Component
@RequiredArgsConstructor
public class EmailVerificationRedisStore {

  private static final String CODE_KEY = "auth:email:%s:%s";
  private static final String ATTEMPTS_KEY = "auth:email:attempts:%s:%s";
  private static final String VERIFIED_KEY = "auth:email:verified:%s:%s";
  private static final String COOLDOWN_KEY = "auth:email:cooldown:%s";
  private static final String DAILY_KEY = "auth:email:daily:%s";

  // KEYS[1] = 재발송 제한 키, KEYS[2] = 하루 발송 키
  // ARGV[1] = 재발송 간격(초), ARGV[2] = 하루 한도, ARGV[3] = 하루 기간(초), ARGV[4] = 요청 식별자
  // 반환 {상태, 남은 밀리초}: 0 = 확보, 1 = 재발송 제한, 2 = 하루 한도 초과. 하루 기간은 첫 발송부터 센다
  // 하루 발송 키는 그 구간에 확보한 요청 식별자 Set (원소 수 = 발송 횟수)
  // 재발송 제한은 키가 있는 동안 막는다 (TTL은 초 단위 내림이라 1초 미만이 남으면 0이 되어 통과할 수 있음)
  @SuppressWarnings("rawtypes")
  private static final RedisScript<List> ACQUIRE_SEND_SCRIPT = new DefaultRedisScript<>("""
      local cooldown = redis.call('PTTL', KEYS[1])
      if cooldown ~= -2 then
        return {1, cooldown}
      end
      if redis.call('SCARD', KEYS[2]) >= tonumber(ARGV[2]) then
        return {2, redis.call('PTTL', KEYS[2])}
      end
      redis.call('SADD', KEYS[2], ARGV[4])
      if redis.call('TTL', KEYS[2]) < 0 then
        redis.call('EXPIRE', KEYS[2], ARGV[3])
      end
      redis.call('SET', KEYS[1], ARGV[4], 'EX', ARGV[1])
      return {0, 0}
      """, List.class);

  // KEYS[1] = 재발송 제한 키, KEYS[2] = 하루 발송 키, (인증 메일일 때만) KEYS[3] = 인증번호 키, KEYS[4] = 틀린 횟수 키
  // ARGV[1] = 요청 식별자. 인증번호 키가 넘어온 경우에만 번호 · 틀린 횟수를 정리한다 (안내 메일은 발송 자리만)
  // 자기 요청이 만든 것만 되돌린다: 재발송 제한 · 인증번호는 값이 자기 식별자일 때만 삭제,
  // 하루 Set에서는 자기 식별자만 뺀다 (구간이 바뀌었으면 새 Set에 없으니 아무것도 빼지 않음, 두 번 불러도 한 번만)
  // 마지막 원소가 빠지면 하루 키도 사라져, 그 구간의 발송이 모두 실패했다면 다음 요청부터 24시간을 새로 센다
  // (서버 쪽 실패는 한도에 넣지 않는다는 정책과 같음)
  private static final RedisScript<Long> RELEASE_SEND_SCRIPT = new DefaultRedisScript<>("""
      if redis.call('GET', KEYS[1]) == ARGV[1] then
        redis.call('DEL', KEYS[1])
      end
      if #KEYS >= 4 then
        local saved = redis.call('GET', KEYS[3])
        if saved and string.sub(saved, 1, #ARGV[1] + 1) == ARGV[1] .. ':' then
          redis.call('DEL', KEYS[3], KEYS[4])
        end
      end
      redis.call('SREM', KEYS[2], ARGV[1])
      return 1
      """, Long.class);

  // KEYS[1] = 인증번호 키, KEYS[2] = 틀린 횟수 키, KEYS[3] = 재발송 제한 키
  // ARGV[1] = 요청 식별자, ARGV[2] = 저장할 값("{요청 식별자}:{번호}"), ARGV[3] = TTL(초)
  // 재발송 제한의 주인일 때만 새 번호 저장과 틀린 횟수 초기화를 한 번에 (1 = 저장, 0 = 주인 아님)
  // 자리를 잡은 뒤 멈춘 사이 다른 요청이 자리를 잡고 새 번호를 보냈다면 그 번호를 덮어쓰지 않는다
  private static final RedisScript<Long> SAVE_CODE_SCRIPT = new DefaultRedisScript<>("""
      if redis.call('GET', KEYS[3]) ~= ARGV[1] then
        return 0
      end
      redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
      redis.call('DEL', KEYS[2])
      return 1
      """, Long.class);

  // KEYS[1] = 인증번호 키, KEYS[2] = 틀린 횟수 키 / ARGV[1] = 입력한 번호, ARGV[2] = 최대 틀린 횟수, ARGV[3] = 틀린 횟수 TTL(초)
  // 인증번호 값은 "{요청 식별자}:{번호}:{묶음 정보}"라 두 번째 ':' 앞의 번호만 비교한다
  // 반환 {상태, 묶음 정보}: 0 = 없음(만료), 1 = 일치(번호 · 횟수 삭제, 묶음 정보 반환), 2 = 불일치, 3 = 최대 횟수만큼 틀림(번호 · 횟수 삭제)
  // 일치했을 때만 묶음 정보를 돌려준다 (번호를 지우는 같은 실행에서 읽으므로, 확인과 대상 정보 사이에 끼어들 틈이 없음)
  @SuppressWarnings("rawtypes")
  private static final RedisScript<List> VERIFY_SCRIPT = new DefaultRedisScript<>("""
      local saved = redis.call('GET', KEYS[1])
      if not saved then
        return {0, ''}
      end
      local first = string.find(saved, ':', 1, true)
      local rest = first and string.sub(saved, first + 1) or saved
      local second = string.find(rest, ':', 1, true)
      local code = second and string.sub(rest, 1, second - 1) or rest
      local binding = second and string.sub(rest, second + 1) or ''
      if code == ARGV[1] then
        redis.call('DEL', KEYS[1], KEYS[2])
        return {1, binding}
      end
      local wrong = redis.call('INCR', KEYS[2])
      if redis.call('TTL', KEYS[2]) < 0 then
        redis.call('EXPIRE', KEYS[2], ARGV[3])
      end
      if wrong >= tonumber(ARGV[2]) then
        redis.call('DEL', KEYS[1], KEYS[2])
        return {3, ''}
      end
      return {2, ''}
      """, List.class);

  private final StringRedisTemplate redisTemplate;

  public enum SendStatus { ACQUIRED, COOLDOWN, DAILY_LIMIT }

  // retryAfterSeconds: 다시 보낼 수 있을 때까지 남은 초 (ACQUIRED면 0)
  // requestId: 확보한 발송 요청의 식별자 (ACQUIRED일 때만). 인증번호 저장 · 되돌리기에 넘긴다
  public record SendResult(SendStatus status, long retryAfterSeconds, String requestId) {
  }

  public enum VerifyResult { MATCHED, MISMATCH, EXHAUSTED, EXPIRED }

  // binding: 인증번호를 저장할 때 함께 넣은 묶음 정보 (MATCHED일 때만, 없으면 빈 문자열)
  public record VerifyOutcome(VerifyResult result, String binding) {
  }

  // ===== 발송 자리 (이메일 단위) =====

  // 재발송 제한 · 하루 한도를 확인하고, 보낼 수 있으면 하루 횟수 +1 · 재발송 제한 시작까지 한 번에 처리
  public SendResult tryAcquireSend(String email, Duration cooldown, int dailyLimit, Duration dailyWindow) {
    String requestId = UUID.randomUUID().toString();
    List<?> result = redisTemplate.execute(ACQUIRE_SEND_SCRIPT,
        List.of(key(COOLDOWN_KEY, email), key(DAILY_KEY, email)),
        String.valueOf(cooldown.toSeconds()), String.valueOf(dailyLimit),
        String.valueOf(dailyWindow.toSeconds()), requestId);
    int status = ((Number) result.get(0)).intValue();
    long remainingSeconds = toRetryAfterSeconds(((Number) result.get(1)).longValue());
    return switch (status) {
      case 0 -> new SendResult(SendStatus.ACQUIRED, 0, requestId);
      case 1 -> new SendResult(SendStatus.COOLDOWN, remainingSeconds, null);
      default -> new SendResult(SendStatus.DAILY_LIMIT, remainingSeconds, null);
    };
  }

  // 메일 발송이 실패하면 그 요청이 만든 상태만 되돌린다 (서버 쪽 실패는 하루 횟수에 넣지 않음)
  public void releaseSend(EmailPurpose purpose, String email, String requestId) {
    redisTemplate.execute(RELEASE_SEND_SCRIPT,
        List.of(key(COOLDOWN_KEY, email), key(DAILY_KEY, email),
            key(CODE_KEY, purpose, email), key(ATTEMPTS_KEY, purpose, email)),
        requestId);
  }

  // 인증번호가 없는 메일(가입 계정 안내)의 발송이 실패하면 발송 자리만 되돌린다 (재발송 제한 · 하루 횟수의 자기 몫)
  public void releaseSend(String email, String requestId) {
    redisTemplate.execute(RELEASE_SEND_SCRIPT, List.of(key(COOLDOWN_KEY, email), key(DAILY_KEY, email)), requestId);
  }

  // ===== 인증번호 (목적별) =====

  // 재발송하면 같은 키에 덮어써 이전 번호는 자동으로 무효, 틀린 횟수도 새로 시작
  // 값은 "{요청 식별자}:{번호}" (되돌리기가 자기 요청의 번호인지 알아볼 수 있게)
  // 재발송 제한의 주인일 때만 저장한다. false면 그 사이 다른 요청이 자리를 잡은 것이라 메일을 보내면 안 된다
  // binding: 이 번호에 묶을 정보 (재설정은 발송 당시 "{회원 id}:{인증 버전}", 가입은 빈 문자열). 확인에 성공하면 그대로 돌려준다
  public boolean saveCode(EmailPurpose purpose, String email, String requestId, String code, String binding,
                          Duration ttl) {
    Long saved = redisTemplate.execute(SAVE_CODE_SCRIPT,
        List.of(key(CODE_KEY, purpose, email), key(ATTEMPTS_KEY, purpose, email), key(COOLDOWN_KEY, email)),
        requestId, requestId + ":" + code + ":" + binding, String.valueOf(ttl.toSeconds()));
    return saved != null && saved == 1L;
  }

  // 비교 · 틀린 횟수 증가 · 폐기를 한 번에 처리 (같은 번호가 동시에 두 번 승인되지 않음)
  public VerifyOutcome verifyCode(EmailPurpose purpose, String email, String code,
                                  int maxWrongAttempts, Duration attemptsTtl) {
    List<?> result = redisTemplate.execute(VERIFY_SCRIPT,
        List.of(key(CODE_KEY, purpose, email), key(ATTEMPTS_KEY, purpose, email)),
        code, String.valueOf(maxWrongAttempts), String.valueOf(attemptsTtl.toSeconds()));
    int status = result == null ? 0 : ((Number) result.get(0)).intValue();
    String binding = result == null || result.get(1) == null ? "" : result.get(1).toString();
    return switch (status) {
      case 1 -> new VerifyOutcome(VerifyResult.MATCHED, binding);
      case 2 -> new VerifyOutcome(VerifyResult.MISMATCH, "");
      case 3 -> new VerifyOutcome(VerifyResult.EXHAUSTED, "");
      default -> new VerifyOutcome(VerifyResult.EXPIRED, "");
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

  // 남은 밀리초를 초로 올림, 최소 1초 (만료 시각이 없거나 막 사라진 경우도 1초)
  private static long toRetryAfterSeconds(long remainingMillis) {
    return Math.max(1, (remainingMillis + 999) / 1000);
  }

  private String key(String pattern, String email) {
    return String.format(pattern, email);
  }

  private String key(String pattern, EmailPurpose purpose, String email) {
    return String.format(pattern, purpose.getKeySegment(), email);
  }
}
