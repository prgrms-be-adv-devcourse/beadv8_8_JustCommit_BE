package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.domain.RevocationReason;
import com.justcommit.backend.member.auth.infrastructure.redis.PasswordResetTokenStore;
import com.justcommit.backend.member.auth.infrastructure.redis.PasswordResetTokenStore.ResetTokenOwner;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

  private static final String EMAIL = "user@example.com";
  private static final Long MEMBER_ID = 7L;
  private static final String TOKEN = "reset-token";
  private static final String NEW_PASSWORD = "newPass123";
  private static final String ENCODED = "encoded-new-password";

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private PasswordResetTokenStore tokenStore;

  @Mock
  private TokenRevocationService tokenRevocationService;

  @Mock
  private PasswordEncoder passwordEncoder;

  @InjectMocks
  private PasswordResetService service;

  @Captor
  private ArgumentCaptor<Consumer<Member>> change;

  // 회원 상태 · 비밀번호 · 인증 버전만 정한 회원 (쓰지 않는 값은 검사하지 않음)
  private Member member(MemberStatus status, String password, int tokenVersion) {
    Member member = mock(Member.class);
    lenient().when(member.getId()).thenReturn(MEMBER_ID);
    lenient().when(member.getStatus()).thenReturn(status);
    lenient().when(member.getPassword()).thenReturn(password);
    lenient().when(member.getTokenVersion()).thenReturn(tokenVersion);
    return member;
  }

  // 토큰 · 해시 · 폐기가 통과하는 재설정 요청을 보내고, revoke()에 넘긴 변경 내용을 꺼낸다
  private Consumer<Member> resetAndCaptureChange(RevocationResult revocationResult) {
    given(tokenStore.take(TOKEN)).willReturn(Optional.of(new ResetTokenOwner(MEMBER_ID, 3)));
    given(passwordEncoder.encode(NEW_PASSWORD)).willReturn(ENCODED);
    given(tokenRevocationService.revoke(eq(MEMBER_ID), eq(RevocationReason.PASSWORD_RESET), any()))
        .willReturn(revocationResult);

    service.reset(TOKEN, NEW_PASSWORD);

    then(tokenRevocationService).should().revoke(eq(MEMBER_ID), eq(RevocationReason.PASSWORD_RESET), change.capture());
    return change.getValue();
  }

  // ===== 재설정 가능 여부 · 토큰 발급 =====

  @Test
  @DisplayName("활동 중인 로컬 회원이면 32바이트 토큰을 만들어 회원 id · 인증 버전과 함께 30분 저장한다")
  void issueToken_activeLocal() {
    Member active = member(MemberStatus.ACTIVE, "hash", 3);
    given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(active));

    Optional<String> token = service.issueToken(EMAIL);

    assertThat(token).isPresent();
    assertThat(token.get()).hasSize(43).matches("[A-Za-z0-9_-]+");
    then(tokenStore).should().save(token.get(), MEMBER_ID, 3, PasswordResetService.RESET_TOKEN_TTL);
  }

  @Test
  @DisplayName("소셜 회원(비밀번호 없음) · 탈퇴 회원 · 없는 이메일은 토큰을 만들지 않는다")
  void issueToken_notResettable() {
    Member social = member(MemberStatus.ACTIVE, null, 3);
    given(memberRepository.findByEmail("social@example.com")).willReturn(Optional.of(social));
    Member deleted = member(MemberStatus.DELETED, "hash", 3);
    given(memberRepository.findByEmail("deleted@example.com")).willReturn(Optional.of(deleted));
    given(memberRepository.findByEmail("none@example.com")).willReturn(Optional.empty());

    assertThat(service.issueToken("social@example.com")).isEmpty();
    assertThat(service.issueToken("deleted@example.com")).isEmpty();
    assertThat(service.issueToken("none@example.com")).isEmpty();
    then(tokenStore).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("재설정 가능 여부: 활동 중인 로컬 회원만 true")
  void canReset() {
    Member active = member(MemberStatus.ACTIVE, "hash", 3);
    given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(active));
    Member social = member(MemberStatus.ACTIVE, null, 3);
    given(memberRepository.findByEmail("social@example.com")).willReturn(Optional.of(social));

    assertThat(service.canReset(EMAIL)).isTrue();
    assertThat(service.canReset("social@example.com")).isFalse();
  }

  // ===== 재설정 =====

  @Test
  @DisplayName("토큰이 없거나 이미 쓴 토큰이면 400 INVALID_RESET_TOKEN, BCrypt · 토큰 폐기 없음")
  void reset_invalidToken() {
    given(tokenStore.take(TOKEN)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.reset(TOKEN, NEW_PASSWORD))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_RESET_TOKEN);

    then(passwordEncoder).shouldHaveNoInteractions();
    then(tokenRevocationService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("재설정 성공: 잠금 안에서 인증 버전이 같으면 새 비밀번호 해시를 저장하고, 폐기 기록 완료면 revocationCompleted true")
  void reset_success() {
    Consumer<Member> resetChange = resetAndCaptureChange(RevocationResult.COMPLETED);
    Member member = member(MemberStatus.ACTIVE, "old-hash", 3);

    resetChange.accept(member);

    then(member).should().changePassword(ENCODED);
  }

  @Test
  @DisplayName("폐기 기록이 재처리 대기(PENDING)면 비밀번호는 바뀌고 revocationCompleted false")
  void reset_pending() {
    given(tokenStore.take(TOKEN)).willReturn(Optional.of(new ResetTokenOwner(MEMBER_ID, 3)));
    given(passwordEncoder.encode(NEW_PASSWORD)).willReturn(ENCODED);
    given(tokenRevocationService.revoke(eq(MEMBER_ID), eq(RevocationReason.PASSWORD_RESET), any()))
        .willReturn(RevocationResult.PENDING);

    assertThat(service.reset(TOKEN, NEW_PASSWORD).revocationCompleted()).isFalse();
  }

  @Test
  @DisplayName("토큰을 받은 뒤 비밀번호가 바뀌어 인증 버전이 다르면 잠금 안에서 INVALID_RESET_TOKEN, 비밀번호 안 바뀜")
  void reset_versionChanged() {
    Consumer<Member> resetChange = resetAndCaptureChange(RevocationResult.COMPLETED);
    Member member = member(MemberStatus.ACTIVE, "old-hash", 4);

    assertThatThrownBy(() -> resetChange.accept(member))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_RESET_TOKEN);
    then(member).should(never()).changePassword(any());
  }

  @Test
  @DisplayName("잠금 안에서 탈퇴 회원 · 소셜 회원이면 INVALID_RESET_TOKEN, 비밀번호 안 바뀜")
  void reset_notResettableInLock() {
    Consumer<Member> resetChange = resetAndCaptureChange(RevocationResult.COMPLETED);
    Member deleted = member(MemberStatus.DELETED, "old-hash", 3);
    Member social = member(MemberStatus.ACTIVE, null, 3);

    assertThatThrownBy(() -> resetChange.accept(deleted))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_RESET_TOKEN);
    assertThatThrownBy(() -> resetChange.accept(social))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.INVALID_RESET_TOKEN);
    then(deleted).should(never()).changePassword(any());
    then(social).should(never()).changePassword(any());
  }

  @Test
  @DisplayName("커밋 결과를 알 수 없으면 503 RESULT_UNKNOWN (자동 재시도하지 않도록)")
  void reset_resultUnknown() {
    given(tokenStore.take(TOKEN)).willReturn(Optional.of(new ResetTokenOwner(MEMBER_ID, 3)));
    given(passwordEncoder.encode(NEW_PASSWORD)).willReturn(ENCODED);
    given(tokenRevocationService.revoke(eq(MEMBER_ID), eq(RevocationReason.PASSWORD_RESET), any()))
        .willThrow(new RevocationOutcomeUnknownException(MEMBER_ID, new RuntimeException("commit unknown")));

    assertThatThrownBy(() -> service.reset(TOKEN, NEW_PASSWORD))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.RESULT_UNKNOWN);
  }
}
