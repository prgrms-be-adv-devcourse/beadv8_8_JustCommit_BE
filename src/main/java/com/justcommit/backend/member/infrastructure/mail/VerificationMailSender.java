package com.justcommit.backend.member.infrastructure.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 인증번호 메일 발송 (Gmail SMTP, 설정은 application.yml의 spring.mail)
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
  public void send(String to, String code, Duration expiresIn) {
    SimpleMailMessage message = new SimpleMailMessage();
    if (!from.isBlank()) {
      message.setFrom(from);
    }
    message.setTo(to);
    message.setSubject("[RePlant] 이메일 인증번호 안내");
    message.setText("""
        RePlant 회원가입 인증번호입니다.

        인증번호: %s

        %d분 안에 입력해주세요.
        본인이 요청하지 않았다면 이 메일을 무시해주세요.
        """.formatted(code, expiresIn.toMinutes()));
    mailSender.send(message);
  }
}
