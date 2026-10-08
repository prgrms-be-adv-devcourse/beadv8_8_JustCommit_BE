package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.exception.EmailCooldownException;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.mail.VerificationMailSender;
import com.justcommit.backend.member.infrastructure.redis.EmailVerificationRedisStore;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

  private static final String EMAIL = "test@example.com";

  @Mock
  private EmailVerificationRedisStore store;

  @Mock
  private VerificationMailSender mailSender;

  @Mock
  private MemberRepository memberRepository;

  @InjectMocks
  private EmailVerificationService service;

  // ===== 발송 =====
  @Test
  @DisplayName("발송 성공: 6자리 번호를 저장하고 같은 번호로 메일 발송")
  void sendCode_success() {
    given(store.tryStartCooldown(EMAIL, EmailVerificationService.COOLDOWN_TTL)).willReturn(true);

    EmailSendResult result = service.sendCode(EMAIL);

    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    then(store).should().saveCode(eq(EMAIL), code.capture(), eq(EmailVerificationService.CODE_TTL));
    then(mailSender).should().send(EMAIL, code.getValue(), EmailVerificationService.CODE_TTL);
    assertThat(code.getValue()).matches("\\d{6}");
    assertThat(result.expiresIn()).isEqualTo(300);
    assertThat(result.resendAvailableIn()).isEqualTo(60);
  }

  @Test
  @DisplayName("이메일은 앞뒤 공백 제거, 소문자로 바꿔서 사용")
  void sendCode_normalizeEmail() {
    given(store.tryStartCooldown(anyString(), any())).willReturn(true);

    service.sendCode("  Test@Example.COM ");

    then(store).should().tryStartCooldown(EMAIL, EmailVerificationService.COOLDOWN_TTL);
  }

  @Test
  @DisplayName("재발송 제한 중이면 남은 시간과 함께 예외, 메일은 보내지 않음")
  void sendCode_cooldown() {
    given(store.tryStartCooldown(EMAIL, EmailVerificationService.COOLDOWN_TTL)).willReturn(false);
    given(store.getCooldownRemainingSeconds(EMAIL)).willReturn(42L);

    assertThatThrownBy(() -> service.sendCode(EMAIL))
            .isInstanceOf(EmailCooldownException.class)
            .extracting("retryAfterSeconds").isEqualTo(42L);

    then(store).should(never()).saveCode(any(), any(), any());
    then(mailSender).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("메일 발송 실패 시 저장한 번호,쿨다운 지우고 EMAIL_SEND_FAILED")
  void sendCode_mailFail() {
    given(store.tryStartCooldown(EMAIL, EmailVerificationService.COOLDOWN_TTL)).willReturn(true);
    willThrow(new MailSendException("fail")).given(mailSender).send(eq(EMAIL), anyString(), any());

    assertThatThrownBy(() -> service.sendCode(EMAIL))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_SEND_FAILED);

    then(store).should().deleteCode(EMAIL);
    then(store).should().deleteCooldown(EMAIL);
  }

  @Test
  @DisplayName("이미 가입된 이메일이면 DUPLICATE_EMAIL, 쿨다운·메일 발송 안 함")
  void sendCode_alreadyRegistered() {
    given(memberRepository.existsByEmail(EMAIL)).willReturn(true);

    assertThatThrownBy(() -> service.sendCode(EMAIL))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);

    then(store).shouldHaveNoInteractions();
    then(mailSender).shouldHaveNoInteractions();
  }

  // ===== 확인 =====
  @Test
  @DisplayName("확인 성공: 번호 삭제 후 인증 완료 저장")
  void verifyCode_success() {
    given(store.findCode(EMAIL)).willReturn(Optional.of("123456"));

    service.verifyCode(EMAIL, "123456");

    then(store).should().deleteCode(EMAIL);
    then(store).should().saveVerified(EMAIL, EmailVerificationService.VERIFIED_TTL);
  }

  @Test
  @DisplayName("번호가 다르면 EMAIL_CODE_MISMATCH, 인증 완료 저장 안 함")
  void verifyCode_mismatch() {
    given(store.findCode(EMAIL)).willReturn(Optional.of("123456"));

    assertThatThrownBy(() -> service.verifyCode(EMAIL, "000000"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_CODE_MISMATCH);

    then(store).should(never()).saveVerified(any(), any());
  }

  @Test
  @DisplayName("저장된 번호가 없으면 EMAIL_CODE_EXPIRED")
  void verifyCode_expired() {
    given(store.findCode(EMAIL)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.verifyCode(EMAIL, "123456"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.EMAIL_CODE_EXPIRED);
  }
}
