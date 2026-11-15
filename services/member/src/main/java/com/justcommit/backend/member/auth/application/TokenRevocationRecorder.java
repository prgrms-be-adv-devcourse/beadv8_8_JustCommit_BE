package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.member.auth.domain.TokenRevocation;
import com.justcommit.backend.member.auth.infrastructure.redis.RevokedMemberRedisStore;
import com.justcommit.backend.member.auth.infrastructure.repository.TokenRevocationRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.ToIntFunction;

/**
 * 폐기 작업 하나를 Redis(auth:revoked-member)에 기록하고 상태를 갱신한다 (#409).
 * 폐기 직후(TokenRevocationService)와 재처리 스케줄러가 같이 쓴다. 트랜잭션 밖에서 호출한다.
 * - TTL = 보관 기한 - DB 현재 시각. 0 이하면 기록하지 않고 EXPIRED
 * - Redis 기록은 RevokedMemberRedisStore의 Lua (폐기 시각이 뒤로 밀리지 않음)라 여러 번 실행해도 안전하다
 * - DB 시각 조회 · Redis 기록 중 어느 쪽이 실패해도 false(→ PENDING). 예외를 밖으로 내보내지 않는다:
 *   호출 시점에는 변경이 이미 커밋돼 있으므로, 기록을 확인하지 못한 것과 변경 실패를 구분해야 한다
 * - 상태 갱신은 별도 트랜잭션의 조건부 UPDATE (PENDING일 때만, 실패 기록은 임대가 그대로일 때만).
 *   실패해도 Redis 기록 결과는 그대로 돌려준다 (PENDING으로 남으면 재처리가 같은 Lua로 다시 기록할 뿐이다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenRevocationRecorder {

  private static final int ERROR_MAX_LENGTH = 500;

  private final RevokedMemberRedisStore revokedMemberRedisStore;
  private final TokenRevocationRepository revocationRepository;
  private final DatabaseClock databaseClock;
  private final TransactionTemplate transactionTemplate;

  /**
   * @return true = Redis 기록 확인 (또는 보관 기한이 지나 기록할 필요 없음), false = 기록을 확인하지 못함 (PENDING 유지)
   */
  public boolean record(TokenRevocation revocation) {
    Duration ttl;
    try {
      ttl = Duration.between(databaseClock.now(), revocation.getRetainUntil());
    } catch (RuntimeException e) {
      return fail(revocation, "DB 시각 조회", e);
    }
    if (ttl.toMillis() <= 0) {
      update(revocation.getId(), id -> revocationRepository.markExpired(id, LocalDateTime.now()));
      return true;
    }
    try {
      revokedMemberRedisStore.recordCutoff(revocation.getMemberId(), revocation.getCutoffAt(), ttl);
    } catch (RuntimeException e) {
      return fail(revocation, "Redis 기록", e);
    }
    update(revocation.getId(), id -> revocationRepository.markRecorded(id, LocalDateTime.now()));
    return true;
  }

  // 실패 기록은 이 실행이 들고 있는 임대(작업의 nextAttemptAt)가 그대로일 때만 (늦게 끝난 실패가 새 임대를 덮어쓰지 않게)
  private boolean fail(TokenRevocation revocation, String step, RuntimeException e) {
    log.warn("폐기 기록 실패({}): revocationId={}, memberId={}", step, revocation.getId(), revocation.getMemberId(), e);
    update(revocation.getId(), id -> revocationRepository.recordFailure(
        id, summary(e), revocation.getNextAttemptAt(), LocalDateTime.now()));
    return false;
  }

  // 별도 트랜잭션에서 조건부 UPDATE 실행. 0행이면 이미 다른 쪽이 끝낸 작업이라 그대로 둔다
  private void update(Long revocationId, ToIntFunction<Long> statement) {
    try {
      transactionTemplate.executeWithoutResult(status -> {
        if (statement.applyAsInt(revocationId) == 0) {
          log.debug("폐기 작업이 이미 PENDING이 아님: revocationId={}", revocationId);
        }
      });
    } catch (RuntimeException e) {
      log.warn("폐기 작업 상태 갱신 실패: revocationId={}", revocationId, e);
    }
  }

  private static String summary(RuntimeException e) {
    String text = e.getClass().getSimpleName() + ": " + e.getMessage();
    return text.length() <= ERROR_MAX_LENGTH ? text : text.substring(0, ERROR_MAX_LENGTH);
  }
}
