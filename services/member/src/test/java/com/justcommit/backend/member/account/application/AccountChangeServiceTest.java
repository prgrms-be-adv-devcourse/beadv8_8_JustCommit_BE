package com.justcommit.backend.member.account.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.account.infrastructure.AccountChangeTokenStore;
import com.justcommit.backend.member.auth.application.EmailSendResult;
import com.justcommit.backend.member.auth.application.EmailVerificationService;
import com.justcommit.backend.member.auth.domain.VerificationBinding;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

// 계좌 변경 인증번호 발송 · 변경 토큰 발급 (#413)
@ExtendWith(MockitoExtension.class)
class AccountChangeServiceTest {

  private static final Long MEMBER_ID = 7L;
  private static final String EMAIL = "user@example.com";
  private static final String CODE = "123456";

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private EmailVerificationService emailVerificationService;

  @Mock
  private AccountChangeTokenStore tokenStore;

  @InjectMocks
  private AccountChangeService service;

  private void givenMember(MemberStatus status, int tokenVersion) {
    Member member = mock(Member.class);
    lenient().when(member.getId()).thenReturn(MEMBER_ID);
    lenient().when(member.getEmail()).thenReturn(EMAIL);
    lenient().when(member.getStatus()).thenReturn(status);
    lenient().when(member.getTokenVersion()).thenReturn(tokenVersion);
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));
  }

  private static void assertErrorCode(Runnable call, MemberErrorCode expected) {
    assertThatThrownBy(call::run)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(expected);
  }

  // ===== 인증번호 발송 =====

  @Test
  @DisplayName("발송: 회원 정보의 이메일로, 지금의 회원 id · 인증 버전을 묶어 보낸다")
  void sendVerificationCode() {
    givenMember(MemberStatus.ACTIVE, 3);
    EmailSendResult sent = new EmailSendResult(300, 60);
    given(emailVerificationService.sendAccountChangeCode(EMAIL, new VerificationBinding(MEMBER_ID, 3))).willReturn(sent);

    assertThat(service.sendVerificationCode(MEMBER_ID)).isEqualTo(sent);
  }

  @Test
  @DisplayName("발송: 활동 중인 회원이 아니면 MEMBER_NOT_FOUND, 메일 없음")
  void sendVerificationCode_notActive() {
    givenMember(MemberStatus.DELETED, 3);

    assertErrorCode(() -> service.sendVerificationCode(MEMBER_ID), MemberErrorCode.MEMBER_NOT_FOUND);
    then(emailVerificationService).shouldHaveNoInteractions();
  }

  // ===== 변경 토큰 발급 =====

  @Test
  @DisplayName("발급: 발송 당시와 같은 회원 · 인증 버전이면 토큰을 만들어 10분 저장하고 그 토큰을 돌려준다")
  void issueChangeToken() {
    givenMember(MemberStatus.ACTIVE, 3);
    given(emailVerificationService.verifyAccountChangeCode(EMAIL, CODE))
        .willReturn(Optional.of(new VerificationBinding(MEMBER_ID, 3)));

    AccountChangeTokenResult result = service.issueChangeToken(MEMBER_ID, CODE);

    ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
    then(tokenStore).should().save(token.capture(), eq(new VerificationBinding(MEMBER_ID, 3)),
        eq(AccountChangeService.CHANGE_TOKEN_TTL));
    assertThat(result.accountChangeToken()).isEqualTo(token.getValue()).hasSize(43); // 32바이트 Base64url (패딩 없음)
    assertThat(result.expiresIn()).isEqualTo(600);
  }

  @Test
  @DisplayName("발급: 인증번호를 받은 뒤 인증 버전이 바뀌었으면(비밀번호 변경 등) ACCOUNT_VERIFICATION_INVALID, 토큰 없음")
  void issueChangeToken_tokenVersionChanged() {
    givenMember(MemberStatus.ACTIVE, 4);
    given(emailVerificationService.verifyAccountChangeCode(EMAIL, CODE))
        .willReturn(Optional.of(new VerificationBinding(MEMBER_ID, 3)));

    assertErrorCode(() -> service.issueChangeToken(MEMBER_ID, CODE), MemberErrorCode.ACCOUNT_VERIFICATION_INVALID);
    then(tokenStore).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("발급: 지금 활동 중인 회원이 아니면 ACCOUNT_VERIFICATION_INVALID, 토큰 없음")
  void issueChangeToken_notActive() {
    givenMember(MemberStatus.INACTIVE, 3);
    given(emailVerificationService.verifyAccountChangeCode(EMAIL, CODE))
        .willReturn(Optional.of(new VerificationBinding(MEMBER_ID, 3)));

    assertErrorCode(() -> service.issueChangeToken(MEMBER_ID, CODE), MemberErrorCode.ACCOUNT_VERIFICATION_INVALID);
    then(tokenStore).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("발급: 묶음 정보의 회원이 로그인 회원과 다르거나 깨졌으면 ACCOUNT_VERIFICATION_INVALID, 토큰 없음")
  void issueChangeToken_otherMemberOrBroken() {
    givenMember(MemberStatus.ACTIVE, 3);
    given(emailVerificationService.verifyAccountChangeCode(EMAIL, CODE))
        .willReturn(Optional.of(new VerificationBinding(8L, 3)), Optional.empty());

    assertErrorCode(() -> service.issueChangeToken(MEMBER_ID, CODE), MemberErrorCode.ACCOUNT_VERIFICATION_INVALID);
    assertErrorCode(() -> service.issueChangeToken(MEMBER_ID, CODE), MemberErrorCode.ACCOUNT_VERIFICATION_INVALID);
    then(tokenStore).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("발급: 인증번호 오류(틀림 등)는 그대로 전달하고 토큰 없음")
  void issueChangeToken_codeMismatch() {
    givenMember(MemberStatus.ACTIVE, 3);
    given(emailVerificationService.verifyAccountChangeCode(EMAIL, CODE))
        .willThrow(new BusinessException(MemberErrorCode.EMAIL_CODE_MISMATCH));

    assertErrorCode(() -> service.issueChangeToken(MEMBER_ID, CODE), MemberErrorCode.EMAIL_CODE_MISMATCH);
    then(tokenStore).shouldHaveNoInteractions();
  }
}
