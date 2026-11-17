package com.justcommit.backend.member.account.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.account.infrastructure.AccountChangeTokenStore;
import com.justcommit.backend.member.auth.application.EmailSendResult;
import com.justcommit.backend.member.auth.application.EmailVerificationService;
import com.justcommit.backend.member.auth.domain.VerificationBinding;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.crypto.MemberHmac;
import com.justcommit.backend.member.infrastructure.lock.MemberLocker;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

// 계좌 변경 인증번호 발송 · 변경 토큰 발급 (#413)
@ExtendWith(MockitoExtension.class)
class AccountChangeServiceTest {

  private static final Long MEMBER_ID = 7L;
  private static final String EMAIL = "user@example.com";
  private static final String CODE = "123456";
  private static final String TOKEN = "change-token";
  private static final VerificationBinding OWNER = new VerificationBinding(MEMBER_ID, 3);
  private static final String BANK = "088";
  private static final String ACCOUNT_NO = "98765432101";
  private static final String HOLDER = "홍길동";
  private static final String OLD_HASH = "old-hash";
  private static final String NEW_HASH = "new-hash";
  private static final AccountChangeCommand CHANGE = new AccountChangeCommand(TOKEN, BANK, ACCOUNT_NO, HOLDER);

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private EmailVerificationService emailVerificationService;

  @Mock
  private AccountChangeTokenStore tokenStore;

  @Mock
  private MemberLocker memberLocker;

  @Mock
  private MemberHmac memberHmac;

  @Mock
  private AccountCipher accountCipher;

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

  // 잠금으로 꺼내는 회원 (계좌 변경). 지금 계좌는 OLD_HASH · 예금주 홍길동
  private Member givenLockedMember(MemberStatus status, int tokenVersion) {
    Member member = mock(Member.class);
    lenient().when(member.getId()).thenReturn(MEMBER_ID);
    lenient().when(member.getStatus()).thenReturn(status);
    lenient().when(member.getTokenVersion()).thenReturn(tokenVersion);
    lenient().when(member.getAccountHash()).thenReturn(OLD_HASH);
    lenient().when(member.getAccountHolder()).thenReturn(HOLDER);
    given(memberLocker.lockById(MEMBER_ID)).willReturn(Optional.of(member));
    return member;
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

  // ===== 계좌 변경: 토큰이 남는 실패 (꺼내기 전) =====

  @Test
  @DisplayName("변경: 토큰이 없거나 만료됐으면 INVALID_ACCOUNT_CHANGE_TOKEN, 잠금 · 꺼내기 없음")
  void changeAccount_tokenNotFound() {
    given(tokenStore.find(TOKEN)).willReturn(Optional.empty());

    assertErrorCode(() -> service.changeAccount(MEMBER_ID, CHANGE), MemberErrorCode.INVALID_ACCOUNT_CHANGE_TOKEN);
    then(memberLocker).shouldHaveNoInteractions();
    then(tokenStore).should(never()).take(anyString());
  }

  @Test
  @DisplayName("변경: 다른 회원의 토큰이면 INVALID_ACCOUNT_CHANGE_TOKEN, 잠금 · 꺼내기 없음")
  void changeAccount_otherMembersToken() {
    given(tokenStore.find(TOKEN)).willReturn(Optional.of(new VerificationBinding(8L, 3)));

    assertErrorCode(() -> service.changeAccount(MEMBER_ID, CHANGE), MemberErrorCode.INVALID_ACCOUNT_CHANGE_TOKEN);
    then(memberLocker).shouldHaveNoInteractions();
    then(tokenStore).should(never()).take(anyString());
  }

  @Test
  @DisplayName("변경: 회원 잠금 대기를 넘으면 MEMBER_BUSY 그대로, 토큰은 꺼내지 않아 남는다")
  void changeAccount_memberBusy() {
    given(tokenStore.find(TOKEN)).willReturn(Optional.of(OWNER));
    given(memberLocker.lockById(MEMBER_ID)).willThrow(new BusinessException(MemberErrorCode.MEMBER_BUSY));

    assertErrorCode(() -> service.changeAccount(MEMBER_ID, CHANGE), MemberErrorCode.MEMBER_BUSY);
    then(tokenStore).should(never()).take(anyString());
  }

  @Test
  @DisplayName("변경: 토큰을 받은 뒤 인증 버전이 바뀌었거나 활동 중이 아니면 INVALID_ACCOUNT_CHANGE_TOKEN, 꺼내기 없음")
  void changeAccount_versionChangedOrNotActive() {
    given(tokenStore.find(TOKEN)).willReturn(Optional.of(OWNER));
    givenLockedMember(MemberStatus.ACTIVE, 4);
    assertErrorCode(() -> service.changeAccount(MEMBER_ID, CHANGE), MemberErrorCode.INVALID_ACCOUNT_CHANGE_TOKEN);

    givenLockedMember(MemberStatus.INACTIVE, 3);
    assertErrorCode(() -> service.changeAccount(MEMBER_ID, CHANGE), MemberErrorCode.INVALID_ACCOUNT_CHANGE_TOKEN);

    then(tokenStore).should(never()).take(anyString());
  }

  @Test
  @DisplayName("변경: 다른 회원이 쓰는 계좌면 DUPLICATE_ACCOUNT, 토큰은 꺼내지 않아 남고 암호화도 없음")
  void changeAccount_duplicateBeforeTake() {
    given(tokenStore.find(TOKEN)).willReturn(Optional.of(OWNER));
    givenLockedMember(MemberStatus.ACTIVE, 3);
    given(memberHmac.accountHash(BANK, ACCOUNT_NO)).willReturn(NEW_HASH);
    given(memberRepository.existsByAccountHashAndIdNot(NEW_HASH, MEMBER_ID)).willReturn(true);

    assertErrorCode(() -> service.changeAccount(MEMBER_ID, CHANGE), MemberErrorCode.DUPLICATE_ACCOUNT);
    then(tokenStore).should(never()).take(anyString());
    then(accountCipher).shouldHaveNoInteractions();
  }

  // ===== 계좌 변경: 토큰을 꺼낸 뒤 =====

  @Test
  @DisplayName("변경: 꺼내려는 사이 같은 토큰을 다른 요청이 썼다면 INVALID_ACCOUNT_CHANGE_TOKEN, 변경 없음")
  void changeAccount_tokenUsedMeanwhile() {
    given(tokenStore.find(TOKEN)).willReturn(Optional.of(OWNER));
    Member member = givenLockedMember(MemberStatus.ACTIVE, 3);
    given(memberHmac.accountHash(BANK, ACCOUNT_NO)).willReturn(NEW_HASH);
    given(tokenStore.take(TOKEN)).willReturn(Optional.empty());

    assertErrorCode(() -> service.changeAccount(MEMBER_ID, CHANGE), MemberErrorCode.INVALID_ACCOUNT_CHANGE_TOKEN);
    then(member).should(never()).changeAccount(any(), any(), any(), any());
  }

  @Test
  @DisplayName("변경: 은행 · 계좌번호 · 예금주가 모두 같으면 토큰만 소모하고 암호화 · 변경 · 저장 없음")
  void changeAccount_sameAccount() {
    given(tokenStore.find(TOKEN)).willReturn(Optional.of(OWNER));
    Member member = givenLockedMember(MemberStatus.ACTIVE, 3);
    given(memberHmac.accountHash(BANK, ACCOUNT_NO)).willReturn(OLD_HASH);
    given(tokenStore.take(TOKEN)).willReturn(Optional.of(OWNER));

    service.changeAccount(MEMBER_ID, CHANGE);

    then(tokenStore).should().take(TOKEN);
    then(accountCipher).shouldHaveNoInteractions();
    then(member).should(never()).changeAccount(any(), any(), any(), any());
    then(memberRepository).should(never()).flush();
  }

  @Test
  @DisplayName("변경: 계좌는 같고 예금주만 다르면 예금주 수정으로 저장한다")
  void changeAccount_holderOnly() {
    given(tokenStore.find(TOKEN)).willReturn(Optional.of(OWNER));
    Member member = givenLockedMember(MemberStatus.ACTIVE, 3);
    given(memberHmac.accountHash(BANK, ACCOUNT_NO)).willReturn(OLD_HASH);
    given(tokenStore.take(TOKEN)).willReturn(Optional.of(OWNER));
    given(accountCipher.encrypt(ACCOUNT_NO)).willReturn("v1:encrypted");

    service.changeAccount(MEMBER_ID, new AccountChangeCommand(TOKEN, BANK, ACCOUNT_NO, "김철수"));

    then(member).should().changeAccount(BANK, "v1:encrypted", OLD_HASH, "김철수");
    then(memberRepository).should().flush();
  }

  @Test
  @DisplayName("변경 성공: 토큰을 꺼낸 뒤 은행 · 암호문 · HMAC · 예금주를 바꾸고 바로 DB에 보낸다")
  void changeAccount_success() {
    given(tokenStore.find(TOKEN)).willReturn(Optional.of(OWNER));
    Member member = givenLockedMember(MemberStatus.ACTIVE, 3);
    given(memberHmac.accountHash(BANK, ACCOUNT_NO)).willReturn(NEW_HASH);
    given(tokenStore.take(TOKEN)).willReturn(Optional.of(OWNER));
    given(accountCipher.encrypt(ACCOUNT_NO)).willReturn("v1:encrypted");

    service.changeAccount(MEMBER_ID, CHANGE);

    then(member).should().changeAccount(BANK, "v1:encrypted", NEW_HASH, HOLDER);
    then(memberRepository).should().flush();
  }

  @Test
  @DisplayName("변경: 꺼낸 뒤 동시 등록으로 계좌 UNIQUE 위반이면 DUPLICATE_ACCOUNT (토큰은 이미 소모)")
  void changeAccount_uniqueViolationAfterTake() {
    given(tokenStore.find(TOKEN)).willReturn(Optional.of(OWNER));
    givenLockedMember(MemberStatus.ACTIVE, 3);
    given(memberHmac.accountHash(BANK, ACCOUNT_NO)).willReturn(NEW_HASH);
    given(tokenStore.take(TOKEN)).willReturn(Optional.of(OWNER));
    given(accountCipher.encrypt(ACCOUNT_NO)).willReturn("v1:encrypted");
    willThrow(new DataIntegrityViolationException("could not execute statement",
        new RuntimeException("duplicate key value violates unique constraint \"uk_member_account_hash\""))).given(memberRepository).flush();

    assertErrorCode(() -> service.changeAccount(MEMBER_ID, CHANGE), MemberErrorCode.DUPLICATE_ACCOUNT);
    then(tokenStore).should().take(TOKEN);
  }
}
