package com.justcommit.backend.member.auth.infrastructure.mail;

import com.justcommit.backend.member.auth.domain.EmailPurpose;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 인증번호 메일 발송 (Gmail SMTP, 설정은 application.yml의 spring.mail)
// #412: 목적(회원가입 · 비밀번호 재설정 · 계좌 변경)에 맞는 제목 · 본문으로 보낸다
@Component
public class VerificationMailSender {

  private final JavaMailSender mailSender;
  private final String from;

  public VerificationMailSender(JavaMailSender mailSender,
                                @Value("${spring.mail.username:}") String from) {
    this.mailSender = mailSender;
    this.from = from;
  }

  // 발송 실패 시 MailException(RuntimeException)이 발생 → 서비스에서 처리
  public void send(String to, String code, Duration expiresIn, EmailPurpose purpose) {
    String label = label(purpose);
    SimpleMailMessage message = new SimpleMailMessage();
    if (!from.isBlank()) {
      message.setFrom(from);
    }
    message.setTo(to);
    message.setSubject("[RePlant] " + label + " 인증번호 안내");
    message.setText("""
        RePlant %s 인증번호입니다.

        인증번호: %s

        %d분 안에 입력해주세요.
        %s
        """.formatted(label, code, expiresIn.toMinutes(), notice(purpose)));
    mailSender.send(message);
  }

  private static String label(EmailPurpose purpose) {
    return switch (purpose) {
      case SIGNUP -> "회원가입";
      case PASSWORD_RESET -> "비밀번호 재설정";
      case ACCOUNT_CHANGE -> "계좌 변경";
    };
  }

  // 본인이 요청하지 않은 메일을 받았을 때의 안내 (재설정은 무시해도 비밀번호가 바뀌지 않음을 알려 줌)
  // 계좌 변경은 로그인한 사람만 요청할 수 있어, 본인이 요청하지 않았다면 계정 보안을 확인하도록 안내한다 (#413)
  private static String notice(EmailPurpose purpose) {
    return switch (purpose) {
      case PASSWORD_RESET -> "본인이 요청하지 않았다면 이 메일을 무시해주세요. 비밀번호는 바뀌지 않습니다.";
      case ACCOUNT_CHANGE -> "본인이 요청하지 않았다면 인증번호를 입력하지 마세요. "
          + "이메일·비밀번호 회원은 비밀번호를 변경하고, 소셜 로그인 회원은 소셜 계정의 보안 설정을 확인해 주세요.";
      default -> "본인이 요청하지 않았다면 이 메일을 무시해주세요.";
    };
  }
}
