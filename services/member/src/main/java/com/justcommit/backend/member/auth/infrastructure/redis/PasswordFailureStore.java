package com.justcommit.backend.member.auth.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 비밀번호 변경 때 현재 비밀번호를 틀린 횟수 (#412, #461 리뷰).
 * 키: auth:password:failures:{회원 id}, 값: "{인증 버전}:{틀린 횟수}"
 * - 인증 버전별로 센다: 저장된 버전이 지금 버전과 다르면 0회로 본다. 비밀번호가 바뀌면(인증 버전 +1) 옛 횟수는 저절로 적용되지 않는다
 * - 같은 버전의 오답은 횟수만 늘리고 만료 시각은 그대로 둔다(첫 오답부터 24시간). 새 버전의 첫 오답부터 새 24시간
 * - 초기화는 변경 전 버전의 횟수일 때만 지운다 (커밋 직후 새 버전에서 쌓인 기록을 늦은 초기화가 지우지 않게)
 * - 판단과 변경은 Lua 한 번으로 처리한다
 */
@Component
@RequiredArgsConstructor
public class PasswordFailureStore {

  private static final String KEY = "auth:password:failures:%s";

  // KEYS[1] / ARGV[1] = 지금 인증 버전, ARGV[2] = 한도
  // 반환: 막혔으면 남은 밀리초(최소 1), 아니면 0. 막힘 여부와 남은 시간을 한 번에 읽는다
  private static final RedisScript<Long> LOCKED_SCRIPT = new DefaultRedisScript<>("""
      local saved = redis.call('GET', KEYS[1])
      if not saved then
        return 0
      end
      local separator = string.find(saved, ':', 1, true)
      if not separator or string.sub(saved, 1, separator - 1) ~= ARGV[1] then
        return 0
      end
      local count = tonumber(string.sub(saved, separator + 1)) or 0
      if count < tonumber(ARGV[2]) then
        return 0
      end
      local remaining = redis.call('PTTL', KEYS[1])
      if remaining < 1 then
        return 1
      end
      return remaining
      """, Long.class);

  // KEYS[1] / ARGV[1] = 지금 인증 버전, ARGV[2] = 기간(초). 반환: 기록한 뒤의 틀린 횟수
  // 같은 버전이면 횟수만 +1 (KEEPTTL로 만료 시각 유지), 없거나 다른 버전이면 "{지금 버전}:1"로 새 기간 시작
  private static final RedisScript<Long> RECORD_SCRIPT = new DefaultRedisScript<>("""
      local saved = redis.call('GET', KEYS[1])
      local separator = saved and string.find(saved, ':', 1, true)
      if separator and string.sub(saved, 1, separator - 1) == ARGV[1] then
        local count = (tonumber(string.sub(saved, separator + 1)) or 0) + 1
        redis.call('SET', KEYS[1], ARGV[1] .. ':' .. count, 'KEEPTTL')
        return count
      end
      redis.call('SET', KEYS[1], ARGV[1] .. ':1', 'EX', ARGV[2])
      return 1
      """, Long.class);

  // KEYS[1] / ARGV[1] = 변경 전 인증 버전. 그 버전의 횟수일 때만 지운다. 반환: 지웠으면 1
  private static final RedisScript<Long> CLEAR_SCRIPT = new DefaultRedisScript<>("""
      local saved = redis.call('GET', KEYS[1])
      if not saved then
        return 0
      end
      local separator = string.find(saved, ':', 1, true)
      if separator and string.sub(saved, 1, separator - 1) == ARGV[1] then
        redis.call('DEL', KEYS[1])
        return 1
      end
      return 0
      """, Long.class);

  private final StringRedisTemplate redisTemplate;

  // 지금 인증 버전에서 한도만큼 틀렸으면 다시 시도할 수 있을 때까지 남은 초(올림, 최소 1), 아니면 0
  public long retryAfterSeconds(Long memberId, int tokenVersion, int limit) {
    Long remainingMillis = redisTemplate.execute(LOCKED_SCRIPT, List.of(key(memberId)),
        String.valueOf(tokenVersion), String.valueOf(limit));
    if (remainingMillis == null || remainingMillis <= 0) {
      return 0;
    }
    return Math.max(1, (remainingMillis + 999) / 1000);
  }

  // 현재 비밀번호를 틀렸을 때 기록. 반환: 기록한 뒤의 틀린 횟수
  public long recordFailure(Long memberId, int tokenVersion, Duration window) {
    Long count = redisTemplate.execute(RECORD_SCRIPT, List.of(key(memberId)),
        String.valueOf(tokenVersion), String.valueOf(window.toSeconds()));
    return count == null ? 0 : count;
  }

  // 비밀번호 변경 · 재설정이 커밋된 뒤, 변경 전 버전의 횟수만 지운다
  public void clear(Long memberId, int tokenVersionBeforeChange) {
    redisTemplate.execute(CLEAR_SCRIPT, List.of(key(memberId)), String.valueOf(tokenVersionBeforeChange));
  }

  private String key(Long memberId) {
    return String.format(KEY, memberId);
  }
}
