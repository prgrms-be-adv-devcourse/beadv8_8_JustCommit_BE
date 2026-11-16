package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.domain.EmailPurpose;
import com.justcommit.backend.member.auth.infrastructure.mail.VerificationMailSender;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore.SendResult;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore.SendStatus;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore.VerifyResult;
import com.justcommit.backend.member.domain.exception.EmailCooldownException;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

  private static final String EMAIL = "test@example.com";
  private static final EmailPurpose SIGNUP = EmailPurpose.SIGNUP;
  private static final String REQUEST_ID = "request-1";

  @Mock
  private EmailVerificationRedisStore store;

  @Mock
  private VerificationMailSender mailSender;

  @Mock
  private MemberRepository memberRepository;

  @InjectMocks
  private EmailVerificationService service;

  // 발송 자리 확보 결과를 정함 (재발송 제한 · 하루 한도는 서비스 상수 그대로 넘어가야 함)
  private void givenSendSlot(SendStatus status, long retryAfterSeconds) {
    given(store.tryAcquireSend(EMAIL, EmailVerificationService.COOLDOWN_TTL,
        EmailVerificationService.DAILY_SEND_LIMIT, EmailVerificationService.DAILY_WINDOW))
        .willReturn(new SendResult(status, retryAfterSeconds, status == SendStatus.ACQUIRED ? REQUEST_ID : null));
  }

  // 인증번호 저장 결과를 정함 (재발송 제한의 주인이면 true)
  private void givenCodeSaved(boolean saved) {
    given(store.saveCode(eq(SIGNUP), eq(EMAIL), eq(REQUEST_ID), anyString(), eq(EmailVerificationService.CODE_TTL)))
        .willReturn(saved);
  }

  // 인증번호 확인 결과를 정함 (틀린 횟수 한도 · TTL은 서비스 상수 그대로 넘어가야 함)
  private void givenVerify(String code, VerifyResult result) {
    given(store.verifyCode(SIGNUP, EMAIL, code,
        EmailVerificationService.MAX_WRONG_ATTEMPTS, EmailVerificationService.CODE_TTL))
        .willReturn(result);
  }

  // ===== 발송 =====
  @Test
  @DisplayName("발송 성공: 가입 목적으로 6자리 번호를 저장하고 같은 번호로 메일 발송")
  void sendCode_success() {
    givenSendSlot(SendStatus.ACQUIRED, 0);
    givenCodeSaved(true);

    EmailSendResult result = service.sendCode(EMAIL, SIGNUP);

    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    then(store).should().saveCode(eq(SIGNUP), eq(EMAIL), eq(REQUEST_ID), code.capture(), eq(EmailVerificationService.CODE_TTL));
    then(mailSender).should().send(EMAIL, code.getValue(), EmailVerificationService.CODE_TTL, SIGNUP);
    assertThat(code.getValue()).matches("\\d{6}");
    assertThat(result.expiresIn()).isEqualTo(300);
    assertThat(result.resendAvailableIn()).isEqualTo(60);
  }

  @Test
  @DisplayName("이메일은 앞뒤 공백 제거, 소문자로 바꿔서 사용")
  void sendCode_normalizeEmail() {
    givenSendSlot(SendStatus.ACQUIRED, 0);
    givenCodeSaved(true);

    service.sendCode("  Test@Example.COM ", SIGNUP);

    then(store).should().saveCode(eq(SIGNUP), eq(EMAIL), eq(REQUEST_ID), anyString(), any());
  }

  @Test
  @DisplayName("재발송 제한 중이면 429 EMAIL_SEND_COOLDOWN과 남은 시간, 저장 · 메일 없음")
  void sendCode_cooldown() {
    givenSendSlot(SendStatus.COOLDOWN, 42);

    assertThatThrownBy(() -> service.sendCode(EMAIL, SIGNUP))
        .isInstanceOfSatisfying(EmailCooldownException.class, e -> {
          assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.EMAIL_SEND_COOLDOWN);
          assertThat(e.getRetryAfterSeconds()).isEqualTo(42L);
        });

    then(store).should(never()).saveCode(any(), any(), any(), any(), any());
    then(mailSender).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("하루 한도를 넘으면 429 EMAIL_SEND_DAILY_LIMIT과 하루 기간 남은 시간, 저장 · 메일 없음")
  void sendCode_dailyLimit() {
    givenSendSlot(SendStatus.DAILY_LIMIT, 3600);

    assertThatThrownBy(() -> service.sendCode(EMAIL, SIGNUP))
        .isInstanceOfSatisfying(EmailCooldownException.class, e -> {
          assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.EMAIL_SEND_DAILY_LIMIT);
          assertThat(e.getRetryAfterSeconds()).isEqualTo(3600L);
        });

    then(store).should(never()).saveCode(any(), any(), any(), any(), any());
    then(mailSender).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("메일 발송 실패 시 이 요청이 만든 번호 · 발송 자리만 되돌리고 EMAIL_SEND_FAILED")
  void sendCode_mailFail() {
    givenSendSlot(SendStatus.ACQUIRED, 0);
    givenCodeSaved(true);
    willThrow(new MailSendException("fail")).given(mailSender).send(eq(EMAIL), anyString(), any(), any());

    assertThatThrownBy(() -> service.sendCode(EMAIL, SIGNUP))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_SEND_FAILED);

    then(store).should().releaseSend(SIGNUP, EMAIL, REQUEST_ID);
  }

  @Test
  @DisplayName("자리를 잡은 사이 다른 요청이 새 번호를 보냈다면 덮어쓰지 않고, 메일 없이 자리를 되돌린 뒤 429 EMAIL_SEND_COOLDOWN")
  void sendCode_superseded() {
    givenSendSlot(SendStatus.ACQUIRED, 0);
    givenCodeSaved(false);

    assertThatThrownBy(() -> service.sendCode(EMAIL, SIGNUP))
        .isInstanceOfSatisfying(EmailCooldownException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.EMAIL_SEND_COOLDOWN));

    then(mailSender).shouldHaveNoInteractions();
    then(store).should().releaseSend(SIGNUP, EMAIL, REQUEST_ID);
  }

  @Test
  @DisplayName("이미 가입된 이메일이면 DUPLICATE_EMAIL, 발송 자리 · 메일 발송 없음")
  void sendCode_alreadyRegistered() {
    given(memberRepository.existsByEmail(EMAIL)).willReturn(true);

    assertThatThrownBy(() -> service.sendCode(EMAIL, SIGNUP))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);

    then(store).shouldHaveNoInteractions();
    then(mailSender).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("아직 열지 않은 목적(계좌 변경)은 EMAIL_PURPOSE_NOT_SUPPORTED, 회원 조회 · 저장 · 메일 없음")
  void sendCode_unsupportedPurpose() {
    assertThatThrownBy(() -> service.sendCode(EMAIL, EmailPurpose.ACCOUNT_CHANGE))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_PURPOSE_NOT_SUPPORTED);

    then(memberRepository).shouldHaveNoInteractions();
    then(store).shouldHaveNoInteractions();
    then(mailSender).shouldHaveNoInteractions();
  }

  // ===== 확인 =====
  @Test
  @DisplayName("확인 성공: 가입 목적의 인증 완료 저장")
  void verifyCode_success() {
    givenVerify("123456", VerifyResult.MATCHED);

    service.verifyCode(EMAIL, "123456", SIGNUP);

    then(store).should().saveVerified(SIGNUP, EMAIL, EmailVerificationService.VERIFIED_TTL);
  }

  @Test
  @DisplayName("번호가 다르면 EMAIL_CODE_MISMATCH, 인증 완료 저장 안 함")
  void verifyCode_mismatch() {
    givenVerify("000000", VerifyResult.MISMATCH);

    assertThatThrownBy(() -> service.verifyCode(EMAIL, "000000", SIGNUP))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_CODE_MISMATCH);

    then(store).should(never()).saveVerified(any(), any(), any());
  }

  @Test
  @DisplayName("5번째로 틀리면 EMAIL_CODE_ATTEMPTS_EXCEEDED (인증번호는 저장소에서 폐기됨)")
  void verifyCode_exhausted() {
    givenVerify("000000", VerifyResult.EXHAUSTED);

    assertThatThrownBy(() -> service.verifyCode(EMAIL, "000000", SIGNUP))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_CODE_ATTEMPTS_EXCEEDED);

    then(store).should(never()).saveVerified(any(), any(), any());
  }

  @Test
  @DisplayName("저장된 번호가 없으면 EMAIL_CODE_EXPIRED")
  void verifyCode_expired() {
    givenVerify("123456", VerifyResult.EXPIRED);

    assertThatThrownBy(() -> service.verifyCode(EMAIL, "123456", SIGNUP))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_CODE_EXPIRED);
  }

  @Test
  @DisplayName("아직 열지 않은 목적(계좌 변경)은 확인도 EMAIL_PURPOSE_NOT_SUPPORTED")
  void verifyCode_unsupportedPurpose() {
    assertThatThrownBy(() -> service.verifyCode(EMAIL, "123456", EmailPurpose.ACCOUNT_CHANGE))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_PURPOSE_NOT_SUPPORTED);

    then(store).shouldHaveNoInteractions();
  }
}
