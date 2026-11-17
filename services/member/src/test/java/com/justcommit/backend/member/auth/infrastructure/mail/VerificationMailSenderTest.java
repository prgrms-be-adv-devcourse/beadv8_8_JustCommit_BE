package com.justcommit.backend.member.auth.infrastructure.mail;

import com.justcommit.backend.member.auth.domain.EmailPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// 인증 메일 문구 (#412): 실제로 보내지 않고 보낼 내용만 확인한다
class VerificationMailSenderTest {

  private JavaMailSender javaMailSender;
  private VerificationMailSender sender;

  @BeforeEach
  void setUp() {
    javaMailSender = mock(JavaMailSender.class);
    sender = new VerificationMailSender(javaMailSender, "noreply@replant.test");
  }

  private SimpleMailMessage sent(EmailPurpose purpose) {
    sender.send("user@example.com", "123456", Duration.ofMinutes(5), purpose);
    ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(javaMailSender).send(message.capture());
    return message.getValue();
  }

  @Test
  @DisplayName("가입 인증 메일은 회원가입 제목 · 본문과 인증번호 · 유효 시간을 담는다")
  void signup() {
    SimpleMailMessage message = sent(EmailPurpose.SIGNUP);

    assertThat(message.getTo()).containsExactly("user@example.com");
    assertThat(message.getSubject()).isEqualTo("[RePlant] 회원가입 인증번호 안내");
    assertThat(message.getText()).contains("회원가입 인증번호", "123456", "5분");
  }

  @Test
  @DisplayName("재설정 인증 메일은 비밀번호 재설정 제목 · 본문과, 무시해도 비밀번호가 바뀌지 않는다는 안내를 담는다")
  void passwordReset() {
    SimpleMailMessage message = sent(EmailPurpose.PASSWORD_RESET);

    assertThat(message.getSubject()).isEqualTo("[RePlant] 비밀번호 재설정 인증번호 안내");
    assertThat(message.getText()).contains("비밀번호 재설정 인증번호", "123456", "비밀번호는 바뀌지 않습니다");
    assertThat(message.getText()).doesNotContain("회원가입");
  }

  @Test
  @DisplayName("계좌 변경 인증 메일은 계좌 변경 제목 · 본문과, 요청하지 않았다면 계정 보안을 확인하라는 안내를 담는다 (#413)")
  void accountChange() {
    SimpleMailMessage message = sent(EmailPurpose.ACCOUNT_CHANGE);

    assertThat(message.getSubject()).isEqualTo("[RePlant] 계좌 변경 인증번호 안내");
    assertThat(message.getText()).contains("계좌 변경 인증번호", "123456", "5분",
        "인증번호를 입력하지 마세요", "비밀번호를 변경", "소셜 계정의 보안 설정");
    assertThat(message.getText()).doesNotContain("이 메일을 무시해주세요");
  }
}
