package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.domain.EmailPurpose;
import com.justcommit.backend.member.auth.domain.EmailSendQuota;
import com.justcommit.backend.member.auth.domain.VerificationBinding;
import com.justcommit.backend.member.auth.infrastructure.mail.VerificationMailSender;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore.SendResult;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore.SendStatus;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore.VerifyOutcome;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore.VerifyResult;
import com.justcommit.backend.member.domain.exception.EmailCooldownException;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

  private static final String EMAIL = "test@example.com";
  // SMTP 응답처럼 수신 주소가 들어간 예외 메시지 (로그에 남지 않아야 함)
  private static final String SMTP_REPLY = "550 5.1.1 <" + EMAIL + "> recipient rejected";
  private static final EmailPurpose SIGNUP = EmailPurpose.SIGNUP;
  private static final String REQUEST_ID = "request-1";
  private static final EmailPurpose RESET = EmailPurpose.PASSWORD_RESET;
  private static final EmailSendQuota SIGNUP_QUOTA = EmailSendQuota.SIGNUP;
  private static final EmailSendQuota RESET_QUOTA = EmailSendQuota.PASSWORD_RESET;
  private static final VerificationBinding TARGET = new VerificationBinding(7L, 3);

  @Mock
  private EmailVerificationRedisStore store;

  @Mock
  private VerificationMailSender mailSender;

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private PasswordResetService passwordResetService;

  private EmailVerificationService service;

  // 기본은 제출한 작업을 바로 실행하는 실행기 (기존 흐름을 순서대로 검증). 비동기 · 제출 거절은 각 테스트에서 따로 만든다 (#421)
  @BeforeEach
  void setUp() {
    service = serviceWith(new SyncTaskExecutor());
  }

  private EmailVerificationService serviceWith(TaskExecutor mailTaskExecutor) {
    return new EmailVerificationService(store, mailSender, memberRepository, passwordResetService, mailTaskExecutor);
  }

  // 대기열이 가득 찬 실행기처럼 제출을 항상 거절
  private static TaskExecutor rejectingExecutor() {
    return task -> {
      throw new TaskRejectedException("mail executor full");
    };
  }

  // 실제 스레드 풀 (스레드 1개). 테스트가 끝나면 shutdown
  private static ThreadPoolTaskExecutor realExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("mail-test-");
    executor.initialize();
    return executor;
  }

  // 발송 자리 확보 결과를 정함 (재발송 제한 · 하루 한도는 서비스 상수 그대로, 하루 한도는 그 용도의 것이어야 함)
  private void givenSendSlot(EmailSendQuota quota, SendStatus status, long retryAfterSeconds) {
    given(store.tryAcquireSend(quota, EMAIL, EmailSendPolicy.COOLDOWN,
        EmailSendPolicy.DAILY_LIMIT, EmailSendPolicy.DAILY_WINDOW))
        .willReturn(new SendResult(status, retryAfterSeconds, status == SendStatus.ACQUIRED ? REQUEST_ID : null));
  }

  // 인증번호 저장 결과를 정함 (재발송 제한의 주인이면 true)
  private void givenCodeSaved(boolean saved) {
    given(store.saveCode(eq(SIGNUP), eq(EMAIL), eq(REQUEST_ID), anyString(), eq(""), eq(EmailVerificationService.CODE_TTL)))
        .willReturn(saved);
  }

  // 인증번호 확인 결과를 정함 (틀린 횟수 한도 · TTL은 서비스 상수 그대로 넘어가야 함)
  private void givenVerify(String code, VerifyResult result) {
    given(store.verifyCode(SIGNUP, EMAIL, code,
        EmailVerificationService.MAX_WRONG_ATTEMPTS, EmailVerificationService.CODE_TTL))
        .willReturn(new VerifyOutcome(result, ""));
  }

  // ===== 발송 =====
  @Test
  @DisplayName("발송 성공: 가입 목적으로 6자리 번호를 저장하고 같은 번호로 메일 발송")
  void sendCode_success() {
    givenSendSlot(SIGNUP_QUOTA, SendStatus.ACQUIRED, 0);
    givenCodeSaved(true);

    EmailSendResult result = service.sendCode(EMAIL, SIGNUP);

    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    then(store).should().saveCode(eq(SIGNUP), eq(EMAIL), eq(REQUEST_ID), code.capture(), eq(""), eq(EmailVerificationService.CODE_TTL));
    then(mailSender).should().send(EMAIL, code.getValue(), EmailVerificationService.CODE_TTL, SIGNUP);
    assertThat(code.getValue()).matches("\\d{6}");
    assertThat(result.expiresIn()).isEqualTo(300);
    assertThat(result.resendAvailableIn()).isEqualTo(60);
  }

  @Test
  @DisplayName("이메일은 앞뒤 공백 제거, 소문자로 바꿔서 사용")
  void sendCode_normalizeEmail() {
    givenSendSlot(SIGNUP_QUOTA, SendStatus.ACQUIRED, 0);
    givenCodeSaved(true);

    service.sendCode("  Test@Example.COM ", SIGNUP);

    then(store).should().saveCode(eq(SIGNUP), eq(EMAIL), eq(REQUEST_ID), anyString(), eq(""), any());
  }

  @Test
  @DisplayName("재발송 제한 중이면 429 EMAIL_SEND_COOLDOWN과 남은 시간, 저장 · 메일 없음")
  void sendCode_cooldown() {
    givenSendSlot(SIGNUP_QUOTA, SendStatus.COOLDOWN, 42);

    assertThatThrownBy(() -> service.sendCode(EMAIL, SIGNUP))
        .isInstanceOfSatisfying(EmailCooldownException.class, e -> {
          assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.EMAIL_SEND_COOLDOWN);
          assertThat(e.getRetryAfterSeconds()).isEqualTo(42L);
        });

    then(store).should(never()).saveCode(any(), any(), any(), any(), any(), any());
    then(mailSender).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("하루 한도를 넘으면 429 EMAIL_SEND_DAILY_LIMIT과 하루 기간 남은 시간, 저장 · 메일 없음")
  void sendCode_dailyLimit() {
    givenSendSlot(SIGNUP_QUOTA, SendStatus.DAILY_LIMIT, 3600);

    assertThatThrownBy(() -> service.sendCode(EMAIL, SIGNUP))
        .isInstanceOfSatisfying(EmailCooldownException.class, e -> {
          assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.EMAIL_SEND_DAILY_LIMIT);
          assertThat(e.getRetryAfterSeconds()).isEqualTo(3600L);
        });

    then(store).should(never()).saveCode(any(), any(), any(), any(), any(), any());
    then(mailSender).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("SMTP 실패 시 메일 작업에서 이 요청이 만든 번호 · 발송 자리만 되돌리고 응답은 그대로, 로그에는 이메일 · 예외 메시지 없이 예외 종류만")
  @ExtendWith(OutputCaptureExtension.class)
  void sendCode_mailFail(CapturedOutput output) {
    givenSendSlot(SIGNUP_QUOTA, SendStatus.ACQUIRED, 0);
    givenCodeSaved(true);
    willThrow(new MailSendException(SMTP_REPLY)).given(mailSender).send(eq(EMAIL), anyString(), any(), any());

    EmailSendResult result = service.sendCode(EMAIL, SIGNUP);

    assertThat(result.expiresIn()).isEqualTo(300);
    then(store).should().releaseSend(SIGNUP, EMAIL, REQUEST_ID);
    assertThat(output.getAll()).contains("purpose=SIGNUP, cause=MailSendException")
        .doesNotContain(EMAIL).doesNotContain(SMTP_REPLY);
  }

  @Test
  @DisplayName("자리를 잡은 사이 다른 요청이 새 번호를 보냈다면 덮어쓰지 않고, 메일 없이 자리를 되돌린 뒤 429 EMAIL_SEND_COOLDOWN")
  void sendCode_superseded() {
    givenSendSlot(SIGNUP_QUOTA, SendStatus.ACQUIRED, 0);
    givenCodeSaved(false);

    assertThatThrownBy(() -> service.sendCode(EMAIL, SIGNUP))
        .isInstanceOfSatisfying(EmailCooldownException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.EMAIL_SEND_COOLDOWN));

    then(mailSender).shouldHaveNoInteractions();
    then(store).should().releaseSend(SIGNUP, EMAIL, REQUEST_ID);
  }

  // ===== 메일 작업 제출 (#421) =====

  @Test
  @DisplayName("메일 작업 제출이 거절되면 이 요청의 예약을 되돌리고 EMAIL_SEND_FAILED, 메일 없음, 로그에는 목적 · 거절 종류만")
  @ExtendWith(OutputCaptureExtension.class)
  void sendCode_submitRejected(CapturedOutput output) {
    service = serviceWith(rejectingExecutor());
    givenSendSlot(SIGNUP_QUOTA, SendStatus.ACQUIRED, 0);
    givenCodeSaved(true);

    assertThatThrownBy(() -> service.sendCode(EMAIL, SIGNUP))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_SEND_FAILED);

    then(store).should().releaseSend(SIGNUP, EMAIL, REQUEST_ID);
    then(mailSender).shouldHaveNoInteractions();
    assertThat(output.getAll()).contains("purpose=SIGNUP, reason=TaskRejectedException").doesNotContain(EMAIL);
  }

  @Test
  @DisplayName("SMTP 호출이 끝나지 않아도 발송 요청은 먼저 반환하고, 정상 발송이면 예약을 되돌리지 않는다")
  void sendCode_returnsBeforeSmtp() throws Exception {
    ThreadPoolTaskExecutor executor = realExecutor();
    try {
      service = serviceWith(executor);
      givenSendSlot(SIGNUP_QUOTA, SendStatus.ACQUIRED, 0);
      givenCodeSaved(true);
      CountDownLatch smtpStarted = new CountDownLatch(1);
      CountDownLatch smtpRelease = new CountDownLatch(1);
      CountDownLatch smtpDone = new CountDownLatch(1);
      willAnswer(invocation -> {
        smtpStarted.countDown();
        smtpRelease.await(5, TimeUnit.SECONDS);
        smtpDone.countDown();
        return null;
      }).given(mailSender).send(eq(EMAIL), anyString(), any(), eq(SIGNUP));

      EmailSendResult result = service.sendCode(EMAIL, SIGNUP);

      // 반환된 시점에 SMTP는 시작됐지만 아직 끝나지 않음
      assertThat(result.expiresIn()).isEqualTo(300);
      assertThat(smtpStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(smtpDone.getCount()).isEqualTo(1);

      smtpRelease.countDown();
      assertThat(smtpDone.await(5, TimeUnit.SECONDS)).isTrue();
      then(store).should(after(200).never()).releaseSend(any(), any(), any());
    } finally {
      executor.shutdown();
    }
  }

  @Test
  @DisplayName("응답 뒤 메일 스레드에서 SMTP가 실패하면 이 요청의 예약을 되돌린다")
  void sendCode_asyncMailFail() {
    ThreadPoolTaskExecutor executor = realExecutor();
    try {
      service = serviceWith(executor);
      givenSendSlot(SIGNUP_QUOTA, SendStatus.ACQUIRED, 0);
      givenCodeSaved(true);
      willThrow(new MailSendException(SMTP_REPLY)).given(mailSender).send(eq(EMAIL), anyString(), any(), eq(SIGNUP));

      assertThat(service.sendCode(EMAIL, SIGNUP).expiresIn()).isEqualTo(300);

      then(store).should(timeout(5000)).releaseSend(SIGNUP, EMAIL, REQUEST_ID);
    } finally {
      executor.shutdown();
    }
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
  @DisplayName("공개 경로는 계좌 변경 목적을 받지 않음 (EMAIL_PURPOSE_NOT_SUPPORTED), 회원 조회 · 저장 · 메일 없음")
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

  // ===== 비밀번호 재설정 발송 (#412): 모든 경우 같은 결과 =====

  @Test
  @DisplayName("재설정 발송: 대상이면 발송 당시 회원 id · 인증 버전을 번호에 묶어 저장하고 재설정 문구로 메일")
  void sendResetCode_target() {
    givenSendSlot(RESET_QUOTA, SendStatus.ACQUIRED, 0);
    given(passwordResetService.findTarget(EMAIL)).willReturn(Optional.of(TARGET));
    given(store.saveCode(eq(RESET), eq(EMAIL), eq(REQUEST_ID), anyString(), eq("7:3"), eq(EmailVerificationService.CODE_TTL)))
        .willReturn(true);

    EmailSendResult result = service.sendCode(EMAIL, RESET);

    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    then(store).should().saveCode(eq(RESET), eq(EMAIL), eq(REQUEST_ID), code.capture(), eq("7:3"), eq(EmailVerificationService.CODE_TTL));
    then(mailSender).should().send(EMAIL, code.getValue(), EmailVerificationService.CODE_TTL, RESET);
    assertThat(result.expiresIn()).isEqualTo(300);
    assertThat(result.resendAvailableIn()).isEqualTo(60);
  }

  @Test
  @DisplayName("재설정 발송: 대상이 아니면(가입 안 됨 · 소셜 · 탈퇴) 자리는 똑같이 잡고 번호 · 메일 없이 같은 결과")
  void sendResetCode_notTarget() {
    givenSendSlot(RESET_QUOTA, SendStatus.ACQUIRED, 0);
    given(passwordResetService.findTarget(EMAIL)).willReturn(Optional.empty());

    EmailSendResult result = service.sendCode(EMAIL, RESET);

    assertThat(result.expiresIn()).isEqualTo(300);
    assertThat(result.resendAvailableIn()).isEqualTo(60);
    then(store).should(never()).saveCode(any(), any(), any(), any(), any(), any());
    then(store).should(never()).releaseSend(any(), any(), any());
    then(mailSender).shouldHaveNoInteractions();
    then(memberRepository).shouldHaveNoInteractions(); // 가입 목적의 가입 여부(409) 검사는 하지 않음
  }

  @Test
  @DisplayName("재설정 발송: 재발송 제한 · 하루 한도에 걸려도 예외 없이 같은 결과, 대상 조회 · 메일 없음")
  void sendResetCode_limited() {
    given(store.tryAcquireSend(RESET_QUOTA, EMAIL, EmailSendPolicy.COOLDOWN,
        EmailSendPolicy.DAILY_LIMIT, EmailSendPolicy.DAILY_WINDOW))
        .willReturn(new SendResult(SendStatus.COOLDOWN, 42, null), new SendResult(SendStatus.DAILY_LIMIT, 3600, null));

    assertThat(service.sendCode(EMAIL, RESET).expiresIn()).isEqualTo(300);
    assertThat(service.sendCode(EMAIL, RESET).expiresIn()).isEqualTo(300);

    then(passwordResetService).shouldHaveNoInteractions();
    then(mailSender).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("재설정 발송: 번호 저장이 거절되면(다른 요청이 자리를 잡음) 이 요청의 예약만 되돌리고 메일 없이 같은 결과")
  void sendResetCode_superseded() {
    givenSendSlot(RESET_QUOTA, SendStatus.ACQUIRED, 0);
    given(passwordResetService.findTarget(EMAIL)).willReturn(Optional.of(TARGET));
    given(store.saveCode(eq(RESET), eq(EMAIL), eq(REQUEST_ID), anyString(), eq("7:3"), eq(EmailVerificationService.CODE_TTL)))
        .willReturn(false);

    assertThat(service.sendCode(EMAIL, RESET).expiresIn()).isEqualTo(300);

    then(store).should().releaseSend(RESET, EMAIL, REQUEST_ID);
    then(mailSender).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("재설정 발송: SMTP가 실패하면 이 요청의 예약만 되돌리고 예외 없이 같은 결과, 로그에는 예외 종류만")
  @ExtendWith(OutputCaptureExtension.class)
  void sendResetCode_mailFail(CapturedOutput output) {
    givenSendSlot(RESET_QUOTA, SendStatus.ACQUIRED, 0);
    given(passwordResetService.findTarget(EMAIL)).willReturn(Optional.of(TARGET));
    given(store.saveCode(eq(RESET), eq(EMAIL), eq(REQUEST_ID), anyString(), eq("7:3"), eq(EmailVerificationService.CODE_TTL)))
        .willReturn(true);
    willThrow(new MailSendException(SMTP_REPLY)).given(mailSender).send(eq(EMAIL), anyString(), any(), eq(RESET));

    assertThat(service.sendCode(EMAIL, RESET).expiresIn()).isEqualTo(300);

    then(store).should().releaseSend(RESET, EMAIL, REQUEST_ID);
    assertThat(output.getAll()).contains("cause=MailSendException")
        .doesNotContain(EMAIL).doesNotContain(SMTP_REPLY);
  }

  @Test
  @DisplayName("재설정 발송: 메일 작업 제출이 거절되면 이 요청의 예약만 되돌리고 예외 없이 같은 결과, 메일 없음")
  void sendResetCode_submitRejected() {
    service = serviceWith(rejectingExecutor());
    givenSendSlot(RESET_QUOTA, SendStatus.ACQUIRED, 0);
    given(passwordResetService.findTarget(EMAIL)).willReturn(Optional.of(TARGET));
    given(store.saveCode(eq(RESET), eq(EMAIL), eq(REQUEST_ID), anyString(), eq("7:3"), eq(EmailVerificationService.CODE_TTL)))
        .willReturn(true);

    assertThat(service.sendCode(EMAIL, RESET).expiresIn()).isEqualTo(300);

    then(store).should().releaseSend(RESET, EMAIL, REQUEST_ID);
    then(mailSender).shouldHaveNoInteractions();
  }

  // ===== 비밀번호 재설정 확인 (#412) =====

  @Test
  @DisplayName("재설정 확인 성공: 묶어 둔 대상으로 토큰을 받아 돌려주고, 인증 완료 표시는 남기지 않는다")
  void verifyResetCode_success() {
    given(store.verifyCode(RESET, EMAIL, "123456",
        EmailVerificationService.MAX_WRONG_ATTEMPTS, EmailVerificationService.CODE_TTL))
        .willReturn(new VerifyOutcome(VerifyResult.MATCHED, "7:3"));
    given(passwordResetService.issueToken(TARGET)).willReturn(Optional.of("reset-token"));

    EmailVerifyResult result = service.verifyCode(EMAIL, "123456", RESET);

    assertThat(result.resetToken()).isEqualTo("reset-token");
    assertThat(result.resetTokenExpiresIn()).isEqualTo(1800);
    then(store).should(never()).saveVerified(any(), any(), any());
  }

  @Test
  @DisplayName("재설정 확인: 틀림 · 만료 · 폐기는 모두 EMAIL_CODE_INVALID, 토큰 발급 시도 없음")
  void verifyResetCode_failuresAreSame() {
    given(store.verifyCode(RESET, EMAIL, "000000",
        EmailVerificationService.MAX_WRONG_ATTEMPTS, EmailVerificationService.CODE_TTL))
        .willReturn(new VerifyOutcome(VerifyResult.MISMATCH, ""),
            new VerifyOutcome(VerifyResult.EXPIRED, ""),
            new VerifyOutcome(VerifyResult.EXHAUSTED, ""));

    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> service.verifyCode(EMAIL, "000000", RESET))
          .isInstanceOf(BusinessException.class)
          .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_CODE_INVALID);
    }
    then(passwordResetService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("재설정 확인: 번호는 맞아도 묶음 정보가 깨졌거나, 그 사이 인증 버전이 바뀌어 토큰을 못 받으면 EMAIL_CODE_INVALID")
  void verifyResetCode_bindingOrVersionInvalid() {
    given(store.verifyCode(RESET, EMAIL, "123456",
        EmailVerificationService.MAX_WRONG_ATTEMPTS, EmailVerificationService.CODE_TTL))
        .willReturn(new VerifyOutcome(VerifyResult.MATCHED, "broken"),
            new VerifyOutcome(VerifyResult.MATCHED, "7:3"));
    given(passwordResetService.issueToken(TARGET)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.verifyCode(EMAIL, "123456", RESET))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_CODE_INVALID);
    assertThatThrownBy(() -> service.verifyCode(EMAIL, "123456", RESET))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_CODE_INVALID);

    then(passwordResetService).should().issueToken(TARGET); // 깨진 묶음 정보로는 발급을 시도하지 않음
    then(passwordResetService).shouldHaveNoMoreInteractions();
  }

  @Test
  @DisplayName("공개 경로는 계좌 변경 목적의 확인도 받지 않음 (EMAIL_PURPOSE_NOT_SUPPORTED)")
  void verifyCode_unsupportedPurpose() {
    assertThatThrownBy(() -> service.verifyCode(EMAIL, "123456", EmailPurpose.ACCOUNT_CHANGE))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_PURPOSE_NOT_SUPPORTED);

    then(store).shouldHaveNoInteractions();
  }

  // ===== 계좌 변경 (#413): 로그인 필수 전용 메서드 =====

  @Test
  @DisplayName("계좌 변경 발송: 계좌 변경 한도로 자리를 잡고, 회원 id · 인증 버전을 번호에 묶어 저장한 뒤 계좌 변경 메일")
  void sendAccountChangeCode_success() {
    givenSendSlot(EmailSendQuota.ACCOUNT_CHANGE, SendStatus.ACQUIRED, 0);
    given(store.saveCode(eq(EmailPurpose.ACCOUNT_CHANGE), eq(EMAIL), eq(REQUEST_ID), anyString(), eq("7:3"),
        eq(EmailVerificationService.CODE_TTL))).willReturn(true);

    EmailSendResult result = service.sendAccountChangeCode(EMAIL, TARGET);

    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    then(store).should().saveCode(eq(EmailPurpose.ACCOUNT_CHANGE), eq(EMAIL), eq(REQUEST_ID), code.capture(), eq("7:3"),
        eq(EmailVerificationService.CODE_TTL));
    then(mailSender).should().send(EMAIL, code.getValue(), EmailVerificationService.CODE_TTL, EmailPurpose.ACCOUNT_CHANGE);
    then(memberRepository).shouldHaveNoInteractions(); // 가입 목적의 가입 여부(409) 검사는 하지 않음
    assertThat(result.expiresIn()).isEqualTo(300);
    assertThat(result.resendAvailableIn()).isEqualTo(60);
  }

  @Test
  @DisplayName("계좌 변경 발송: 재발송 제한이면 429 EMAIL_SEND_COOLDOWN과 남은 시간을 알리고 메일 없음 (로그인한 본인)")
  void sendAccountChangeCode_cooldown() {
    givenSendSlot(EmailSendQuota.ACCOUNT_CHANGE, SendStatus.COOLDOWN, 42);

    assertThatThrownBy(() -> service.sendAccountChangeCode(EMAIL, TARGET))
        .isInstanceOfSatisfying(EmailCooldownException.class, e -> {
          assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.EMAIL_SEND_COOLDOWN);
          assertThat(e.getRetryAfterSeconds()).isEqualTo(42L);
        });

    then(mailSender).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("계좌 변경 확인: 맞으면 발송 때 묶은 회원 정보를 돌려주고, 인증 완료 상태는 저장하지 않음")
  void verifyAccountChangeCode_matched() {
    given(store.verifyCode(EmailPurpose.ACCOUNT_CHANGE, EMAIL, "123456",
        EmailVerificationService.MAX_WRONG_ATTEMPTS, EmailVerificationService.CODE_TTL))
        .willReturn(new VerifyOutcome(VerifyResult.MATCHED, "7:3"));

    assertThat(service.verifyAccountChangeCode(EMAIL, "123456")).contains(TARGET);
    then(store).should(never()).saveVerified(any(), any(), any());
  }

  @Test
  @DisplayName("계좌 변경 확인: 번호는 맞아도 묶음 정보가 깨졌으면 빈 값")
  void verifyAccountChangeCode_brokenBinding() {
    given(store.verifyCode(EmailPurpose.ACCOUNT_CHANGE, EMAIL, "123456",
        EmailVerificationService.MAX_WRONG_ATTEMPTS, EmailVerificationService.CODE_TTL))
        .willReturn(new VerifyOutcome(VerifyResult.MATCHED, "broken"));

    assertThat(service.verifyAccountChangeCode(EMAIL, "123456")).isEmpty();
  }

  @Test
  @DisplayName("계좌 변경 확인: 틀림 · 만료 · 오답 한도는 가입처럼 각 오류로 알림")
  void verifyAccountChangeCode_failures() {
    given(store.verifyCode(EmailPurpose.ACCOUNT_CHANGE, EMAIL, "000000",
        EmailVerificationService.MAX_WRONG_ATTEMPTS, EmailVerificationService.CODE_TTL))
        .willReturn(new VerifyOutcome(VerifyResult.MISMATCH, ""),
            new VerifyOutcome(VerifyResult.EXPIRED, ""),
            new VerifyOutcome(VerifyResult.EXHAUSTED, ""));

    assertThatThrownBy(() -> service.verifyAccountChangeCode(EMAIL, "000000"))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_CODE_MISMATCH);
    assertThatThrownBy(() -> service.verifyAccountChangeCode(EMAIL, "000000"))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_CODE_EXPIRED);
    assertThatThrownBy(() -> service.verifyAccountChangeCode(EMAIL, "000000"))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_CODE_ATTEMPTS_EXCEEDED);
  }
}
