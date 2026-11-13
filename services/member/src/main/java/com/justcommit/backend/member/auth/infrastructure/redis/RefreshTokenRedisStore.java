package com.justcommit.backend.member.auth.infrastructure.redis;

import com.justcommit.backend.common.security.TokenHash;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * Refresh 토큰 Redis 관리 (원문은 저장하지 않고 공통 TokenHash의 SHA-256 해시를 키로 사용, 블랙리스트와 같은 규칙).
 * - auth:refresh:{토큰 해시} → memberId (TTL 1일)
 * - auth:refresh-set:{memberId} → 그 회원의 Refresh 토큰 해시 Set (모든 기기 끊기에 사용)
 *
 * 각 쓰기는 Lua 스크립트 하나로 실행해 스크립트 중간에 다른 명령이 끼어들지 않게 한다.
 * 스크립트가 접근하는 키는 모두 KEYS로 넘긴다: 키를 알아야 하면 스크립트 밖에서 먼저 읽고,
 * 스크립트 안에서 그 사이 바뀌지 않았는지 다시 확인한 뒤 바꾼다.
 *
 * 쓰기 순서: 저장은 Set 추가 → Set TTL → Refresh 키 저장, 삭제는 Refresh 키 삭제 → Set에서 제거.
 * 중간에 실패해도 살아 있는 토큰이 목록에서 빠지지 않고, 목록에 불필요한 해시만 남는다.
 * Redis 오류는 그대로 올려 보낸다(호출한 쪽은 500). 결과를 모르는 쓰기는 자동 재시도하지 않는다.
 *
 * 한계: 만료된 Refresh의 해시는 Set에 남을 수 있다. 새 토큰 발급이 계속되면 Set TTL도 갱신되므로,
 * 오래된 해시가 장기간 누적될 수 있다. (정리 방식은 실제 사용량을 보고 정함)
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenRedisStore {

  // 키: auth:refresh:{토큰 해시}, 값: 회원 ID
  private static final String REFRESH_KEY = "auth:refresh:%s";
  private static final String REFRESH_SET_KEY = "auth:refresh-set:%s";

  // KEYS[1] = Set 키, KEYS[2] = Refresh 키 / ARGV[1] = 해시, ARGV[2] = memberId, ARGV[3] = TTL(초)
  private static final RedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>("""
      redis.call('SADD', KEYS[1], ARGV[1])
      redis.call('EXPIRE', KEYS[1], ARGV[3])
      redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
      return 1
      """, Long.class);

  // KEYS[1] = Refresh 키, KEYS[2] = Set 키 / ARGV[1] = 밖에서 읽은 memberId, ARGV[2] = 해시
  // 값이 그대로일 때만 삭제하고 memberId 반환. 그 사이 지워졌거나 바뀌었으면 아무것도 하지 않고 nil
  private static final RedisScript<String> TAKE_SCRIPT = new DefaultRedisScript<>("""
      local memberId = redis.call('GET', KEYS[1])
      if memberId ~= ARGV[1] then
        return nil
      end
      redis.call('DEL', KEYS[1])
      redis.call('SREM', KEYS[2], ARGV[2])
      return memberId
      """, String.class);

  // KEYS[1] = Set 키, KEYS[2..] = 조회한 해시들의 Refresh 키 / ARGV = 조회한 해시들 (KEYS[2..]와 같은 순서)
  // Set을 통째로 지우지 않고 조회한 해시만 제거 (조회 이후 저장된 토큰은 Set에 남김)
  private static final RedisScript<Long> DELETE_ALL_SCRIPT = new DefaultRedisScript<>("""
      for i = 2, #KEYS do
        redis.call('DEL', KEYS[i])
      end
      for i = 1, #ARGV do
        redis.call('SREM', KEYS[1], ARGV[i])
      end
      return #ARGV
      """, Long.class);

  private final StringRedisTemplate redisTemplate;

  // 새 Refresh 토큰 저장 (로그인 · 소셜 로그인 · 소셜 가입 · 재발급 모두 RefreshTokenIssuer를 거쳐 여기로)
  public void save(String refreshToken, Long memberId, Duration ttl) {
    String hash = TokenHash.sha256Hex(refreshToken);
    String member = String.valueOf(memberId);
    redisTemplate.execute(SAVE_SCRIPT, List.of(setKey(member), refreshKey(hash)),
        hash, member, String.valueOf(ttl.toSeconds()));
  }

  // 재발급: 토큰을 꺼내며 삭제. 동시에 같은 토큰으로 두 요청이 와도 한 요청만 memberId를 얻음
  public Optional<Long> findAndDelete(String refreshToken) {
    String hash = TokenHash.sha256Hex(refreshToken);
    String refreshKey = refreshKey(hash);
    String memberId = redisTemplate.opsForValue().get(refreshKey);
    if (memberId == null) {
      return Optional.empty();
    }
    String taken = redisTemplate.execute(TAKE_SCRIPT, List.of(refreshKey, setKey(memberId)), memberId, hash);
    return Optional.ofNullable(taken).map(Long::valueOf);
  }

  // 로그아웃: 재발급과 같은 방식으로 삭제 (없는 토큰이면 아무것도 하지 않음)
  public void delete(String refreshToken) {
    findAndDelete(refreshToken);
  }

  /**
   * 조회한 회원별 Refresh 토큰을 삭제한다. 조회 이후 발급된 토큰은 삭제 대상에 포함되지 않는다.
   * (탈퇴 #409 · 비밀번호 변경 #411에서 사용. 새 발급까지 막는 처리는 그 이슈에서 함께 설계)
   *
   * @return 삭제 대상으로 조회한 토큰 수
   */
  public int deleteAllByMember(Long memberId) {
    String setKey = setKey(String.valueOf(memberId));
    Set<String> hashes = redisTemplate.opsForSet().members(setKey);
    if (hashes == null || hashes.isEmpty()) {
      return 0;
    }
    List<String> hashList = List.copyOf(hashes);
    List<String> keys = new ArrayList<>(hashList.size() + 1);
    keys.add(setKey);
    hashList.forEach(hash -> keys.add(refreshKey(hash)));
    Long count = redisTemplate.execute(DELETE_ALL_SCRIPT, keys, hashList.toArray());
    return count == null ? 0 : count.intValue();
  }

  private static String refreshKey(String hash) {
    return String.format(REFRESH_KEY, hash);
  }

  private static String setKey(String memberId) {
    return String.format(REFRESH_SET_KEY, memberId);
  }
}
