package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.member.auth.domain.TokenRevocation;
import com.justcommit.backend.member.auth.infrastructure.repository.TokenRevocationRepository;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * 폐기 작업 재처리 (#409, docs/reference/schedulers.md 「토큰 폐기 재처리」).
 * 한 건씩: 짧은 트랜잭션에서 FOR UPDATE SKIP LOCKED로 잡고 next_attempt_at을 30초 뒤로 미룬(임대) 뒤 바로 커밋 →
 * 트랜잭션 밖에서 최초 기록과 같은 Recorder · Lua로 기록. Redis를 기다리는 동안 DB 트랜잭션을 열어 두지 않는다.
 * 보관 기한까지 끝까지 시도한다 (횟수 제한 없음, RECORDED 또는 EXPIRED로 끝남).
 */
@Service
@RequiredArgsConstructor
public class TokenRevocationRetryService {

  // 임대 시간: 처리 중 앱이 죽어도 이 시간이 지나면 다른 실행이 다시 집는다
  static final Duration LEASE = Duration.ofSeconds(30);
  // 한 주기에 처리할 최대 건수 (한 주기가 너무 길어지지 않게)
  static final int MAX_PER_RUN = 50;

  private final TransactionTemplate transactionTemplate;
  private final TokenRevocationRepository revocationRepository;
  private final DatabaseClock databaseClock;
  private final TokenRevocationRecorder revocationRecorder;

  /**
   * @return 이번 주기에 잡아서 처리한 건수
   */
  public int retryPending() {
    int processed = 0;
    while (processed < MAX_PER_RUN) {
      Optional<TokenRevocation> claimed = claimNext();
      if (claimed.isEmpty()) {
        break;
      }
      revocationRecorder.record(claimed.get());
      processed++;
    }
    return processed;
  }

  // 한 건 잡아 임대하고 커밋. 돌려준 작업의 nextAttemptAt이 이 실행의 임대 값 (실패 기록의 조건)
  Optional<TokenRevocation> claimNext() {
    return transactionTemplate.execute(status -> revocationRepository.findNextRetryTargetForUpdate()
        .map(revocation -> {
          revocation.lease(databaseClock.now().plus(LEASE));
          return revocation;
        }));
  }
}
