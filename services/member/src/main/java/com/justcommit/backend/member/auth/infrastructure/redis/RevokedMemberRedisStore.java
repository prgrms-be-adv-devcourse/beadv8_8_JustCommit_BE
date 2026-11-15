package com.justcommit.backend.member.auth.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 회원 토큰 폐기 시각 (#409). member가 쓰고 Gateway TokenRevocationChecker가 읽는다.
 * - 키 auth:revoked-member:{memberId}, 값 = 폐기 시각(초). Gateway는 iat <= 값인 Access를 거절한다
 * - 최초 기록과 재처리가 같은 Lua를 쓴다: 폐기 시각은 뒤로 밀리지 않는다
 *   · 저장된 값이 더 최신이면 값 · 만료 유지
 *   · 같은 폐기 시각이면 더 긴 보관 기한을 유지 (보관 여유 설정을 바꾸는 배포 대비)
 *   · 더 오래됐거나 숫자가 아니거나 없으면 새 값으로 기록
 * - TTL은 호출하는 쪽이 DB 시각으로 계산한 밀리초. 0 이하면 기록하지 않는다(호출 전에 걸러야 함)
 * Redis 오류는 그대로 올려 보낸다 (기록 실패를 성공으로 숨기지 않음).
 */
@Component
@RequiredArgsConstructor
public class RevokedMemberRedisStore {

  private static final String REVOKED_MEMBER_KEY = "auth:revoked-member:%s";

  // KEYS[1] = 폐기 키 / ARGV[1] = 폐기 시각(초), ARGV[2] = 보관 TTL(밀리초)
  // 반환: 1 = 새 값으로 기록, 0 = 같거나 더 최신 값 유지 (둘 다 기록 확인으로 봄)
  private static final RedisScript<Long> RECORD_SCRIPT = new DefaultRedisScript<>("""
      local current = tonumber(redis.call('GET', KEYS[1]))
      local cutoff = tonumber(ARGV[1])
      local ttl = tonumber(ARGV[2])
      if current and current > cutoff then
        return 0
      end
      if current and current == cutoff then
        if redis.call('PTTL', KEYS[1]) < ttl then
          redis.call('PEXPIRE', KEYS[1], ttl)
        end
        return 0
      end
      redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
      return 1
      """, Long.class);

  private final StringRedisTemplate redisTemplate;

  /**
   * @param cutoffAt 폐기 시각 (초 단위로 저장)
   * @param ttl      보관 기한까지 남은 시간 (양수)
   * @return 새 값으로 기록했으면 true, 같거나 더 최신 값이 있어 유지했으면 false
   */
  public boolean recordCutoff(long memberId, Instant cutoffAt, Duration ttl) {
    if (ttl.toMillis() <= 0) {
      throw new IllegalArgumentException("보관 TTL은 양수여야 합니다: " + ttl);
    }
    Long result = redisTemplate.execute(RECORD_SCRIPT,
        List.of(String.format(REVOKED_MEMBER_KEY, memberId)),
        String.valueOf(cutoffAt.getEpochSecond()), String.valueOf(ttl.toMillis()));
    return result != null && result == 1L;
  }
}
