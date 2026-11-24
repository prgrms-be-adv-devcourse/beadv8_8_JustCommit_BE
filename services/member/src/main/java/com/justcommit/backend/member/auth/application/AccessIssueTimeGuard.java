package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.infrastructure.repository.TokenRevocationRepository;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Access 발급 시각 확인 (#546). 로그인 · 소셜 로그인 · 재발급이 같이 쓴다.
 * Gateway는 iat <= 끊은 시각인 Access를 거절하므로, 새 Access는 DB 시각의 초가 회원의 가장 늦은 폐기 시각의 초보다 클 때만 발급한다.
 * - 같은 초면 발급하지 않고 트랜잭션을 롤백한 뒤(회원 잠금 해제) 다음 초까지 기다렸다가 작업을 처음부터 다시 실행한다.
 *   다시 실행하므로 회원 잠금 · 상태 · 인증 정보 · 인증 버전 · 폐기 시각이 모두 다시 확인된다
 * - 발급 시각은 DatabaseClock으로 읽은 값 그대로다. 앞당기거나 미루어 적지 않는다
 * - 시도는 MAX_ATTEMPTS번까지. 그래도 같은 초면 409 MEMBER_BUSY
 * 반드시 트랜잭션 밖에서 runWithRetry를 호출한다: 안에서 부르면 기다리는 동안 회원 잠금이 풀리지 않는다
 */
@Component
@RequiredArgsConstructor
public class AccessIssueTimeGuard {

  static final int MAX_ATTEMPTS = 2;
  // 한 번에 기다리는 시간의 상한. 정상이면 다음 초까지 1초 이하다 (DB 시계가 역행한 경우에만 더 길어짐)
  static final Duration MAX_WAIT = Duration.ofSeconds(1);

  private final TransactionTemplate transactionTemplate;
  private final TokenRevocationRepository revocationRepository;
  private final DatabaseClock databaseClock;

  // issue를 트랜잭션 하나로 실행한다. issue 안의 issuableNow가 아직 이르다고 알리면 롤백 → 대기 → 다시 실행
  // 그 밖의 예외(BusinessException 등)는 다시 시도하지 않고 그대로 올려 보낸다
  public <T> T runWithRetry(Supplier<T> issue) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Access 발급은 트랜잭션 밖에서 시작해야 합니다.");
    }
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        return transactionTemplate.execute(status -> issue.get());
      } catch (IssueTooEarlyException e) {
        if (attempt < MAX_ATTEMPTS) {
          waitOutsideTransaction(e.waitTime);
        }
      }
    }
    throw new BusinessException(MemberErrorCode.MEMBER_BUSY);
  }

  /**
   * 지금 발급해도 되면 발급 시각(DB 시각)을 돌려준다.
   * 호출 조건: runWithRetry의 작업 안에서, 회원 행을 잠근 뒤 호출한다.
   * 잠금이 있어야 폐기 시각을 읽은 뒤 발급할 때까지 새 폐기가 끼어들지 못한다
   */
  public Instant issuableNow(Long memberId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("발급 시각 확인은 회원을 잠근 트랜잭션 안에서 해야 합니다.");
    }
    Optional<Instant> latestCutoffAt = revocationRepository.findLatestCutoffAt(memberId);
    Instant now = databaseClock.now();
    if (latestCutoffAt.isPresent() && now.getEpochSecond() <= latestCutoffAt.get().getEpochSecond()) {
      Instant issuableFrom = Instant.ofEpochSecond(latestCutoffAt.get().getEpochSecond() + 1);
      throw new IssueTooEarlyException(Duration.between(now, issuableFrom));
    }
    return now;
  }

  // 밀리초로 바꿀 때 버려지는 만큼 일찍 깨지 않도록 1ms 올림
  private void waitOutsideTransaction(Duration waitTime) {
    long millis = Math.min(waitTime.toMillis() + 1, MAX_WAIT.toMillis());
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(MemberErrorCode.MEMBER_BUSY);
    }
  }

  // 발급 시각이 폐기 시각과 같은 초라는 내부 신호. 이 클래스 밖으로 나가지 않는다 (스택 추적 없음)
  private static final class IssueTooEarlyException extends RuntimeException {

    private final Duration waitTime;

    private IssueTooEarlyException(Duration waitTime) {
      super("폐기 시각과 같은 초라 아직 발급할 수 없습니다.", null, false, false);
      this.waitTime = waitTime;
    }
  }
}
