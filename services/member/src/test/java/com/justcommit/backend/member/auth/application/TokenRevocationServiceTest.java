package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.domain.RevocationReason;
import com.justcommit.backend.member.auth.domain.TokenRevocation;
import com.justcommit.backend.member.auth.infrastructure.redis.RefreshTokenRedisStore;
import com.justcommit.backend.member.auth.infrastructure.repository.TokenRevocationRepository;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.infrastructure.lock.MemberLocker;
import com.justcommit.backend.member.infrastructure.time.DatabaseClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verify;

/**
 * 토큰 폐기 서비스의 커밋 결과 구분 (#409). 커밋 단계 실패는 실제 DB로 재현하기 어려워 TransactionTemplate을 mock으로 둔다.
 * mock은 본문(콜백)을 실제로 실행한 뒤, 커밋 단계에서 날 예외를 던진다.
 */
@ExtendWith(MockitoExtension.class)
class TokenRevocationServiceTest {

  private static final Long MEMBER_ID = 4L;
  private static final Instant NOW = Instant.parse("2026-11-15T00:00:00.123Z");

  @Mock
  private TransactionTemplate transactionTemplate;
  @Mock
  private MemberLocker memberLocker;
  @Mock
  private DatabaseClock databaseClock;
  @Mock
  private TokenRevocationRepository revocationRepository;
  @Mock
  private TokenRevocationRecorder revocationRecorder;
  @Mock
  private RefreshTokenRedisStore refreshTokenRedisStore;

  private TokenRevocationService service;

  @BeforeEach
  void setUp() {
    service = new TokenRevocationService(transactionTemplate, memberLocker, databaseClock, revocationRepository,
        revocationRecorder, refreshTokenRedisStore, Duration.ofMinutes(30), Duration.ofSeconds(60));
  }

  @Test
  @DisplayName("본문 flush 뒤 커밋 단계 예외: RevocationOutcomeUnknownException, Redis 기록 · Refresh 정리는 하지 않는다")
  void commitFailureAfterFlushIsUnknown() {
    stubBodyReachesFlush();
    runBodyThenThrow(new TransactionSystemException("커밋 응답 끊김"));

    assertThatThrownBy(() -> service.revoke(MEMBER_ID, RevocationReason.PASSWORD_CHANGED, m -> { }))
        .isInstanceOf(RevocationOutcomeUnknownException.class)
        .hasCauseInstanceOf(TransactionSystemException.class);

    then(revocationRecorder).shouldHaveNoInteractions();
    then(refreshTokenRedisStore).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("롤백이 확인된 경우(UnexpectedRollbackException)는 불명이 아니라 그대로 던진다")
  void confirmedRollbackIsRethrown() {
    stubBodyReachesFlush();
    runBodyThenThrow(new UnexpectedRollbackException("롤백됨"));

    assertThatThrownBy(() -> service.revoke(MEMBER_ID, RevocationReason.PASSWORD_CHANGED, m -> { }))
        .isInstanceOf(UnexpectedRollbackException.class);

    then(revocationRecorder).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("flush 전 예외(없는 회원)는 불명이 아니라 그대로 던진다")
  void failureBeforeFlushIsRethrown() {
    given(memberLocker.lockById(MEMBER_ID)).willReturn(Optional.empty());
    given(transactionTemplate.execute(any())).willAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(new SimpleTransactionStatus());
    });

    assertThatThrownBy(() -> service.revoke(MEMBER_ID, RevocationReason.PASSWORD_CHANGED, m -> { }))
        .isInstanceOf(BusinessException.class);

    then(revocationRecorder).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("보관 기한 = 폐기 시각(초 단위 버림) + Access 수명 30분 + 여유 60초")
  void retentionIsAccessLifetimePlusMargin() {
    stubBodyReachesFlush();
    given(transactionTemplate.execute(any())).willAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(new SimpleTransactionStatus());
    });
    given(revocationRecorder.record(any())).willReturn(true);

    assertThat(service.revoke(MEMBER_ID, RevocationReason.PASSWORD_CHANGED, m -> { }))
        .isEqualTo(RevocationResult.COMPLETED);

    ArgumentCaptor<TokenRevocation> captor = ArgumentCaptor.forClass(TokenRevocation.class);
    verify(revocationRepository).saveAndFlush(captor.capture());
    TokenRevocation saved = captor.getValue();
    assertThat(saved.getCutoffAt()).isEqualTo(Instant.parse("2026-11-15T00:00:00Z"));
    assertThat(Duration.between(saved.getCutoffAt(), saved.getRetainUntil())).isEqualTo(Duration.ofMinutes(31));
    assertThat(saved.getTokenVersion()).isEqualTo(1);
  }

  @Test
  @DisplayName("보관 여유가 음수면 생성할 때 예외")
  void negativeMarginRejected() {
    assertThatThrownBy(() -> new TokenRevocationService(transactionTemplate, memberLocker, databaseClock,
        revocationRepository, revocationRecorder, refreshTokenRedisStore,
        Duration.ofMinutes(30), Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // 본문이 saveAndFlush까지 가도록: 회원 잠금 · DB 시각 · 저장
  private void stubBodyReachesFlush() {
    Member member = Member.createLocal("revoke@test.com", "{bcrypt}x", "폐기", "01300000000",
        "004", "v1:test-account", null, "홍길동");
    given(memberLocker.lockById(MEMBER_ID)).willReturn(Optional.of(member));
    given(databaseClock.now()).willReturn(NOW);
    given(revocationRepository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));
  }

  // 본문을 실제로 실행한 뒤 커밋 단계 예외를 흉내 냄
  private void runBodyThenThrow(RuntimeException commitFailure) {
    given(transactionTemplate.execute(any())).willAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      callback.doInTransaction(new SimpleTransactionStatus());
      throw commitFailure;
    });
  }
}
