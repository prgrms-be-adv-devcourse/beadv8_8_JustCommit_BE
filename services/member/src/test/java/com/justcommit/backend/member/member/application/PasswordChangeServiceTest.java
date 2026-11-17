package com.justcommit.backend.member.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.application.RevocationOutcomeUnknownException;
import com.justcommit.backend.member.auth.application.RevocationResult;
import com.justcommit.backend.member.auth.application.TokenRevocationService;
import com.justcommit.backend.member.auth.domain.RevocationReason;
import com.justcommit.backend.member.auth.infrastructure.redis.PasswordFailureStore;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.domain.exception.PasswordChangeLockedException;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionSystemException;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

// 비밀번호 변경 (#411, #412). 실제 커밋 실패 · 회원 잠금은 재현하기 어려워 폐기 서비스를 mock으로 두고,
// mock이 받은 변경 내용(람다)을 실제로 실행해 잠금 안의 확인 · 기록 순서와 변경 전 버전 보관을 확인한다
@ExtendWith(MockitoExtension.class)
class PasswordChangeServiceTest {

  private static final Long MEMBER_ID = 4L;
  private static final String CURRENT = "plant1234";
  private static final String NEW = "green5678";
  private static final String CURRENT_HASH = "current-hash";
  private static final String ENCODED = "encoded-new";
  private static final int LIMIT = PasswordChangeService.MAX_CURRENT_PASSWORD_FAILURES;

  @Mock
  private TokenRevocationService tokenRevocationService;
  @Mock
  private PasswordEncoder passwordEncoder;
  @Mock
  private MemberRepository memberRepository;
  @Mock
  private PasswordFailureStore failureStore;

  @InjectMocks
  private PasswordChangeService passwordChangeService;

  // 활동 중인 로컬 회원 (인증 버전만 다르게)
  private Member member(int tokenVersion) {
    Member member = mock(Member.class);
    lenient().when(member.getStatus()).thenReturn(MemberStatus.ACTIVE);
    lenient().when(member.getPassword()).thenReturn(CURRENT_HASH);
    lenient().when(member.getTokenVersion()).thenReturn(tokenVersion);
    return member;
  }

  // 잠금 전 조회(버전 3)와 잠금 안 회원(버전 4)을 다르게 둔다: 같은 값이면 잘못된 버전을 써도 테스트가 통과할 수 있음
  private Member givenBeforeLockVersion3AndLockedMember() {
    Member beforeLock = member(3);
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(beforeLock));
    return member(4);
  }

  // revoke()가 받은 변경 내용을 잠근 회원에게 실제로 실행하고 결과를 돌려준다 (변경 내용의 예외는 그대로 전달)
  private void givenRevokeRuns(Member lockedMember, RevocationResult result) {
    given(tokenRevocationService.revoke(eq(MEMBER_ID), eq(RevocationReason.PASSWORD_CHANGED), any()))
        .willAnswer(invocation -> {
          Consumer<Member> change = invocation.getArgument(2);
          change.accept(lockedMember);
          return result;
        });
  }

  @Test
  @DisplayName("커밋 결과 불명이면 503 RESULT_UNKNOWN, 새 비밀번호로 로그인해 확인하라는 메시지, 실패 횟수는 지우지 않음")
  void outcomeUnknownBecomes503() {
    given(tokenRevocationService.revoke(eq(MEMBER_ID), eq(RevocationReason.PASSWORD_CHANGED), any()))
        .willThrow(new RevocationOutcomeUnknownException(MEMBER_ID, new TransactionSystemException("커밋 응답 끊김")));

    assertThatThrownBy(() -> passwordChangeService.changePassword(MEMBER_ID, CURRENT, NEW))
        .isInstanceOf(BusinessException.class)
        .hasMessage("비밀번호 변경 결과를 확인하지 못했습니다. 새 비밀번호로 로그인해 확인해 주세요.")
        .satisfies(e -> {
          BusinessException be = (BusinessException) e;
          assertThat(be.getErrorCode()).isEqualTo(MemberErrorCode.RESULT_UNKNOWN);
          assertThat(be.getErrorCode().getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        });
    then(failureStore).should(never()).clear(anyLong(), anyInt());
  }

  @Test
  @DisplayName("잠금 전에 이미 막혔으면 BCrypt · 회원 잠금 없이 429 PASSWORD_CHANGE_LOCKED와 남은 초")
  void lockedBeforeLock() {
    Member beforeLock = member(3);
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(beforeLock));
    given(failureStore.retryAfterSeconds(MEMBER_ID, 3, LIMIT)).willReturn(120L);

    assertThatThrownBy(() -> passwordChangeService.changePassword(MEMBER_ID, CURRENT, NEW))
        .isInstanceOfSatisfying(PasswordChangeLockedException.class, e -> {
          assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.PASSWORD_CHANGE_LOCKED);
          assertThat(e.getRetryAfterSeconds()).isEqualTo(120L);
        });
    then(passwordEncoder).shouldHaveNoInteractions();
    then(tokenRevocationService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("현재 비밀번호가 틀리면 잠금 안에서 잠근 회원의 버전으로 확인 → 비교 → 기록 순서로 처리하고 400")
  void wrongPasswordRecordedInLock() {
    Member locked = givenBeforeLockVersion3AndLockedMember();
    given(passwordEncoder.encode(NEW)).willReturn(ENCODED);
    given(passwordEncoder.matches(CURRENT, CURRENT_HASH)).willReturn(false);
    givenRevokeRuns(locked, RevocationResult.COMPLETED);

    assertThatThrownBy(() -> passwordChangeService.changePassword(MEMBER_ID, CURRENT, NEW))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_CURRENT_PASSWORD);

    InOrder order = inOrder(failureStore, passwordEncoder);
    order.verify(failureStore).retryAfterSeconds(MEMBER_ID, 4, LIMIT);
    order.verify(passwordEncoder).matches(CURRENT, CURRENT_HASH);
    order.verify(failureStore).recordFailure(MEMBER_ID, 4, PasswordChangeService.FAILURE_WINDOW);
    then(failureStore).should(never()).clear(anyLong(), anyInt());
    then(locked).should(never()).changePassword(any());
  }

  @Test
  @DisplayName("잠금 전에는 통과했지만 잠금 안에서 막혔으면 429, 현재 비밀번호를 비교하지도 기록하지도 않음")
  void lockedInsideLock() {
    Member locked = givenBeforeLockVersion3AndLockedMember();
    given(passwordEncoder.encode(NEW)).willReturn(ENCODED);
    given(failureStore.retryAfterSeconds(MEMBER_ID, 3, LIMIT)).willReturn(0L); // 잠금 전(버전 3)에는 통과
    given(failureStore.retryAfterSeconds(MEMBER_ID, 4, LIMIT)).willReturn(30L); // 잠금 안(버전 4)에서 막힘
    givenRevokeRuns(locked, RevocationResult.COMPLETED);

    assertThatThrownBy(() -> passwordChangeService.changePassword(MEMBER_ID, CURRENT, NEW))
        .isInstanceOfSatisfying(PasswordChangeLockedException.class,
            e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(30L));
    then(passwordEncoder).should(never()).matches(any(), any());
    then(failureStore).should(never()).recordFailure(anyLong(), anyInt(), any());
  }

  @Test
  @DisplayName("새 비밀번호가 현재와 같아서 거절되면 실패 횟수에 넣지 않는다")
  void samePasswordNotCounted() {
    Member locked = givenBeforeLockVersion3AndLockedMember();
    given(passwordEncoder.encode(CURRENT)).willReturn(ENCODED);
    given(passwordEncoder.matches(CURRENT, CURRENT_HASH)).willReturn(true);
    givenRevokeRuns(locked, RevocationResult.COMPLETED);

    assertThatThrownBy(() -> passwordChangeService.changePassword(MEMBER_ID, CURRENT, CURRENT))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.SAME_AS_CURRENT_PASSWORD);
    then(failureStore).should(never()).recordFailure(anyLong(), anyInt(), any());
  }

  @Test
  @DisplayName("변경 성공(COMPLETED): 잠금 전 조회 버전(3)이 아니라 잠금 안에서 확인한 변경 전 버전(4)의 실패 횟수를 지운다")
  void successClearsVersionCheckedInLock() {
    Member locked = givenBeforeLockVersion3AndLockedMember();
    given(passwordEncoder.encode(NEW)).willReturn(ENCODED);
    given(passwordEncoder.matches(CURRENT, CURRENT_HASH)).willReturn(true);
    givenRevokeRuns(locked, RevocationResult.COMPLETED);

    PasswordChangeResult result = passwordChangeService.changePassword(MEMBER_ID, CURRENT, NEW);

    assertThat(result.revocationCompleted()).isTrue();
    then(locked).should().changePassword(ENCODED);
    then(failureStore).should().clear(MEMBER_ID, 4);
    then(failureStore).should(never()).clear(MEMBER_ID, 3);
  }

  @Test
  @DisplayName("폐기 기록 재처리 대기(PENDING)도 커밋된 것이라 변경 전 버전의 실패 횟수를 지운다")
  void pendingAlsoClears() {
    Member locked = givenBeforeLockVersion3AndLockedMember();
    given(passwordEncoder.encode(NEW)).willReturn(ENCODED);
    given(passwordEncoder.matches(CURRENT, CURRENT_HASH)).willReturn(true);
    givenRevokeRuns(locked, RevocationResult.PENDING);

    PasswordChangeResult result = passwordChangeService.changePassword(MEMBER_ID, CURRENT, NEW);

    assertThat(result.revocationCompleted()).isFalse();
    then(failureStore).should().clear(MEMBER_ID, 4);
  }

  @Test
  @DisplayName("커밋 뒤 실패 횟수를 지우지 못해도 성공으로 응답한다 (새 인증 버전에는 옛 횟수가 적용되지 않음)")
  void clearFailureStillSucceeds() {
    Member locked = givenBeforeLockVersion3AndLockedMember();
    given(passwordEncoder.encode(NEW)).willReturn(ENCODED);
    given(passwordEncoder.matches(CURRENT, CURRENT_HASH)).willReturn(true);
    givenRevokeRuns(locked, RevocationResult.COMPLETED);
    willThrow(new RedisConnectionFailureException("redis down")).given(failureStore).clear(MEMBER_ID, 4);

    assertThat(passwordChangeService.changePassword(MEMBER_ID, CURRENT, NEW).revocationCompleted()).isTrue();
  }

  @Test
  @DisplayName("잠금 안에서 오답을 기록하지 못하면 변경을 중단하고 서버 오류로 넘긴다")
  void recordFailureErrorAborts() {
    Member locked = givenBeforeLockVersion3AndLockedMember();
    given(passwordEncoder.encode(NEW)).willReturn(ENCODED);
    given(passwordEncoder.matches(CURRENT, CURRENT_HASH)).willReturn(false);
    givenRevokeRuns(locked, RevocationResult.COMPLETED);
    willThrow(new RedisConnectionFailureException("redis down"))
        .given(failureStore).recordFailure(MEMBER_ID, 4, PasswordChangeService.FAILURE_WINDOW);

    assertThatThrownBy(() -> passwordChangeService.changePassword(MEMBER_ID, CURRENT, NEW))
        .isInstanceOf(RedisConnectionFailureException.class);
    then(locked).should(never()).changePassword(any());
  }
}
