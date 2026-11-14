package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.member.auth.domain.RevocationStatus;
import com.justcommit.backend.member.auth.domain.TokenRevocation;
import com.justcommit.backend.member.auth.infrastructure.redis.RevokedMemberRedisStore;
import com.justcommit.backend.member.auth.infrastructure.repository.TokenRevocationRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * 폐기 작업 하나를 Redis(auth:revoked-member)에 기록하고 상태를 갱신한다 (#409).
 * 폐기 직후(TokenRevocationService)와 재처리 스케줄러가 같이 쓴다. 트랜잭션 밖에서 호출한다.
 * - TTL = 보관 기한 - DB 현재 시각. 0 이하면 기록하지 않고 EXPIRED
 * - Redis 기록은 RevokedMemberRedisStore의 Lua (폐기 시각이 뒤로 밀리지 않음)라 여러 번 실행해도 안전하다
 * - 상태 갱신은 별도 트랜잭션. 실패해도 Redis 기록 결과는 그대로 돌려준다
 *   (PENDING으로 남으면 재처리가 같은 Lua로 다시 기록할 뿐이다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenRevocationRecorder {

  // 기록 실패 후 다음 시도까지 (재처리 간격은 스케줄러 작업에서 함께 정리)
  static final Duration RETRY_DELAY = Duration.ofSeconds(10);

  private final RevokedMemberRedisStore revokedMemberRedisStore;
  private final TokenRevocationRepository revocationRepository;
  private final DatabaseClock databaseClock;
  private final TransactionTemplate transactionTemplate;

  /**
   * @return true = Redis 기록 확인 (또는 보관 기한이 지나 기록할 필요 없음), false = 기록 실패 (PENDING 유지)
   */
  public boolean record(TokenRevocation revocation) {
    Instant now = databaseClock.now();
    Duration ttl = Duration.between(now, revocation.getRetainUntil());
    if (ttl.toMillis() <= 0) {
      updateIfPending(revocation.getId(), TokenRevocation::markExpired);
      return true;
    }
    try {
      revokedMemberRedisStore.recordCutoff(revocation.getMemberId(), revocation.getCutoffAt(), ttl);
    } catch (RuntimeException e) {
      log.warn("폐기 시각 Redis 기록 실패: revocationId={}, memberId={}",
          revocation.getId(), revocation.getMemberId(), e);
      updateIfPending(revocation.getId(), r -> r.recordFailure(summary(e), now.plus(RETRY_DELAY)));
      return false;
    }
    updateIfPending(revocation.getId(), TokenRevocation::markRecorded);
    return true;
  }

  // PENDING일 때만 바꾼다: 재처리와 겹쳐도 이미 RECORDED · EXPIRED인 작업을 되돌리지 않는다
  private void updateIfPending(Long revocationId, Consumer<TokenRevocation> change) {
    try {
      transactionTemplate.executeWithoutResult(status -> revocationRepository.findById(revocationId)
          .filter(r -> r.getStatus() == RevocationStatus.PENDING)
          .ifPresent(change));
    } catch (RuntimeException e) {
      log.warn("폐기 작업 상태 갱신 실패: revocationId={}", revocationId, e);
    }
  }

  private static String summary(RuntimeException e) {
    return e.getClass().getSimpleName() + ": " + e.getMessage();
  }
}
