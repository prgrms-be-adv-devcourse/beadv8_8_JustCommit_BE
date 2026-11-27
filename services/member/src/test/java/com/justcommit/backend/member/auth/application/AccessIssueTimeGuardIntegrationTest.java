package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.member.support.MemberIntegrationTest;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.domain.RevocationReason;
import com.justcommit.backend.member.auth.infrastructure.repository.TokenRevocationRepository;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.lock.MemberLocker;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.doReturn;

/**
 * Access 발급 시각 확인 (#546)을 실제 PostgreSQL로 확인한다.
 * - 테스트 클래스에 @Transactional을 붙이지 않는다: 가드가 여는 트랜잭션의 커밋 · 롤백을 그대로 본다
 * - 작업은 실제 발급 경로처럼 회원을 잠그고, 발급 전 변경을 흉내 내 인증 버전을 1 올린 뒤 발급 시각을 확인한다.
 *   롤백됐는지는 커밋된 인증 버전으로 확인한다
 * - 같은 초는 DatabaseClock을 고정해 만든다 (다음 초도 고정: 실제 시각에 따라 결과가 달라지지 않게)
 */
class AccessIssueTimeGuardIntegrationTest extends MemberIntegrationTest {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired
  private AccessIssueTimeGuard guard;

  @Autowired
  private TokenRevocationRepository revocationRepository;

  @Autowired
  private MemberLocker memberLocker;

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private StringRedisTemplate redisTemplate;

  private final List<Long> memberIds = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (Long id : memberIds) {
      jdbcTemplate.update("delete from member_token_revocation where member_id = ?", id);
      jdbcTemplate.update("delete from member where id = ?", id);
    }
    try (var connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  @Test
  @DisplayName("[S-A08 · #546] 폐기 기록이 없으면 기다리지 않고 DB 시각을 발급 시각으로 돌려준다")
  void noRevocation_issuesWithoutWaiting() {
    Member member = saveMember();
    AtomicInteger runs = new AtomicInteger();

    Instant issuedAt = guard.runWithRetry(issue(member.getId(), runs));

    assertThat(issuedAt).isNotNull();
    assertThat(runs).hasValue(1);
    assertThat(tokenVersionOf(member.getId())).isEqualTo(1);
  }

  @Test
  @DisplayName("[S-A08 · #546] 폐기가 이전 초면 기다리지 않고 발급한다")
  void revokedInEarlierSecond_issuesWithoutWaiting() {
    Member member = saveMember();
    long cutoff = revoke(member.getId());
    doReturn(Instant.ofEpochSecond(cutoff + 1)).when(databaseClock).now();
    AtomicInteger runs = new AtomicInteger();

    Instant issuedAt = guard.runWithRetry(issue(member.getId(), runs));

    assertThat(issuedAt.getEpochSecond()).isGreaterThan(cutoff);
    assertThat(runs).hasValue(1);
  }

  @Test
  @DisplayName("[S-A08 · #546] 폐기와 같은 초면 첫 시도를 롤백하고, 기다린 뒤 다시 실행해 폐기 시각보다 뒤인 초에 발급한다")
  void sameSecond_rollsBackThenIssuesInLaterSecond() {
    Member member = saveMember();
    long cutoff = revoke(member.getId());
    doReturn(Instant.ofEpochSecond(cutoff).plusMillis(500), Instant.ofEpochSecond(cutoff + 1))
        .when(databaseClock).now();
    AtomicInteger runs = new AtomicInteger();

    Instant issuedAt = guard.runWithRetry(issue(member.getId(), runs));

    assertThat(issuedAt.getEpochSecond()).isGreaterThan(cutoff);
    assertThat(runs).hasValue(2);
    // 폐기가 올린 1 + 두 번째 시도의 1. 첫 시도의 변경이 커밋됐다면 3이다
    assertThat(tokenVersionOf(member.getId())).isEqualTo(2);
  }

  @Test
  @DisplayName("[S-A08 · #546] 다시 실행해도 같은 초면 MEMBER_BUSY로 끝나고, 시도한 변경은 남지 않는다")
  void stillSameSecond_memberBusy() {
    Member member = saveMember();
    long cutoff = revoke(member.getId());
    doReturn(Instant.ofEpochSecond(cutoff).plusMillis(500)).when(databaseClock).now();
    AtomicInteger runs = new AtomicInteger();

    assertThatThrownBy(() -> guard.runWithRetry(issue(member.getId(), runs)))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(MemberErrorCode.MEMBER_BUSY);

    assertThat(runs).hasValue(AccessIssueTimeGuard.MAX_ATTEMPTS);
    assertThat(tokenVersionOf(member.getId())).isEqualTo(1);
  }

  @Test
  @DisplayName("[S-A08 · #546] 작업이 던진 다른 예외는 다시 시도하지 않고 그대로 올려 보낸다")
  void otherException_notRetried() {
    AtomicInteger runs = new AtomicInteger();

    assertThatThrownBy(() -> guard.runWithRetry(() -> {
      runs.incrementAndGet();
      throw new BusinessException(MemberErrorCode.LOGIN_FAILED);
    }))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(MemberErrorCode.LOGIN_FAILED);

    assertThat(runs).hasValue(1);
  }

  @Test
  @DisplayName("[S-A08 · #546] 트랜잭션 안에서 시작하면 거부한다 (기다리는 동안 회원 잠금이 풀리지 않으므로)")
  void insideTransaction_rejected() {
    assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
        status -> guard.runWithRetry(() -> "issued")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("[S-A08 · #546] 기다리는 중 인터럽트되면 인터럽트 상태를 유지한 채 MEMBER_BUSY로 끝난다")
  void interruptedWhileWaiting_memberBusyAndInterruptKept() throws InterruptedException {
    Member member = saveMember();
    long cutoff = revoke(member.getId());
    // 초의 시작으로 고정 → 대기 시간이 상한(1초)이라 그 사이에 인터럽트할 수 있다
    doReturn(Instant.ofEpochSecond(cutoff)).when(databaseClock).now();
    AtomicInteger runs = new AtomicInteger();
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicBoolean interruptKept = new AtomicBoolean();

    // 테스트 스레드의 인터럽트 상태를 건드리지 않도록 별도 스레드에서 실행
    Thread worker = new Thread(() -> {
      try {
        guard.runWithRetry(issue(member.getId(), runs));
      } catch (Throwable e) {
        thrown.set(e);
      }
      interruptKept.set(Thread.currentThread().isInterrupted());
    }, "issue-worker");
    worker.start();
    awaitSleeping(worker, runs);

    worker.interrupt();
    worker.join(5_000);

    assertThat(worker.isAlive()).isFalse();
    assertThat(thrown.get()).isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_BUSY);
    assertThat(interruptKept).isTrue();
    assertThat(runs).hasValue(1);
  }

  // 실제 발급 경로처럼: 회원 잠금 → 발급 전 변경(인증 버전 +1로 흉내) → 발급 시각 확인
  private Supplier<Instant> issue(Long memberId, AtomicInteger runs) {
    return () -> {
      runs.incrementAndGet();
      Member member = memberLocker.lockById(memberId).orElseThrow();
      member.increaseTokenVersion();
      return guard.issuableNow(memberId);
    };
  }

  // 첫 시도가 끝나(롤백) 가드가 트랜잭션 밖에서 기다리는 상태가 될 때까지 확인
  private static void awaitSleeping(Thread worker, AtomicInteger runs) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (runs.get() == 1 && worker.getState() == Thread.State.TIMED_WAITING) {
        return;
      }
      Thread.sleep(10);
    }
    fail("가드가 대기 상태에 들어가지 않았습니다.");
  }

  // 실제 폐기 처리 → 가장 늦은 폐기 시각(초)
  private long revoke(Long memberId) {
    tokenRevocationService.revoke(memberId, RevocationReason.PASSWORD_CHANGED, member -> { });
    return revocationRepository.findLatestCutoffAt(memberId).orElseThrow().getEpochSecond();
  }

  private int tokenVersionOf(Long memberId) {
    return memberRepository.findById(memberId).orElseThrow().getTokenVersion();
  }

  private Member saveMember() {
    int n = SEQ.incrementAndGet();
    Member member = memberRepository.save(Member.createLocal(
        "guard" + n + "@test.com", passwordEncoder.encode("plant1234"), "가드" + n, String.format("015%08d", n),
        "004", "v1:test-account", null, "홍길동"));
    memberIds.add(member.getId());
    return member;
  }
}
