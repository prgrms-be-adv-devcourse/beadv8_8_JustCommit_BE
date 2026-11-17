package com.justcommit.backend.member.auth.infrastructure.mail;

import com.justcommit.backend.member.domain.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 가입 계정 안내 메일 문구 (#412): 실제로 보내지 않고 보낼 내용만 확인한다
class AccountReminderMailSenderTest {

  private JavaMailSender javaMailSender;
  private AccountReminderMailSender sender;

  @BeforeEach
  void setUp() {
    javaMailSender = mock(JavaMailSender.class);
    sender = new AccountReminderMailSender(javaMailSender, "noreply@replant.test");
  }

  private SimpleMailMessage sent(Provider provider) {
    sender.send("user@example.com", provider);
    ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(javaMailSender).send(message.capture());
    return message.getValue();
  }

  @Test
  @DisplayName("이메일 로그인 회원: 가입 계정 안내 제목, 이메일 로그인과 비밀번호 재설정 안내")
  void local() {
    SimpleMailMessage message = sent(Provider.LOCAL);

    assertThat(message.getTo()).containsExactly("user@example.com");
    assertThat(message.getSubject()).isEqualTo("[RePlant] 가입 계정 안내");
    assertThat(message.getText()).contains("가입된 RePlant 계정이 있습니다", "이메일 로그인", "비밀번호 재설정");
  }

  @Test
  @DisplayName("카카오 회원: 카카오 로그인 안내, 비밀번호 재설정 안내는 없음")
  void kakao() {
    SimpleMailMessage message = sent(Provider.KAKAO);

    assertThat(message.getText()).contains("카카오 로그인").doesNotContain("비밀번호 재설정");
  }

  @Test
  @DisplayName("안내 문구가 없는 로그인 방식(네이버)이면 예외, 메일은 보내지 않는다")
  void unsupportedProvider() {
    assertThatThrownBy(() -> sender.send("user@example.com", Provider.NAVER))
        .isInstanceOf(IllegalArgumentException.class);
    verify(javaMailSender, never()).send(any(SimpleMailMessage.class));
  }
}
