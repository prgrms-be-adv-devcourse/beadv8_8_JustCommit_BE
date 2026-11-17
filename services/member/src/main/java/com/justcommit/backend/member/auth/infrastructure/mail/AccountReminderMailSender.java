package com.justcommit.backend.member.auth.infrastructure.mail;

import com.justcommit.backend.member.domain.Provider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

// 가입 계정 안내 메일 (#412, 아이디 찾기). 로그인 방식에 맞는 안내를 보낸다 (Gmail SMTP, 설정은 application.yml의 spring.mail)
@Component
public class AccountReminderMailSender {

  private final JavaMailSender mailSender;
  private final String from;

  public AccountReminderMailSender(JavaMailSender mailSender,
                                   @Value("${spring.mail.username:}") String from) {
    this.mailSender = mailSender;
    this.from = from;
  }

  // 발송 실패 시 MailException(RuntimeException) → 서비스에서 처리
  // 안내 문구가 없는 로그인 방식이면 IllegalArgumentException (잘못된 안내를 조용히 보내지 않게)
  public void send(String to, Provider provider) {
    String guide = switch (provider) {
      case LOCAL -> """
          로그인 방식: 이메일 로그인

          가입한 이메일과 비밀번호로 로그인해 주세요.
          비밀번호가 기억나지 않으면 로그인 화면의 '비밀번호 재설정'을 이용해 주세요.
          """;
      case KAKAO -> """
          로그인 방식: 카카오 로그인

          로그인 화면에서 '카카오로 로그인'을 이용해 주세요.
          """;
      default -> throw new IllegalArgumentException("안내 문구가 없는 로그인 방식입니다: " + provider);
    };
    SimpleMailMessage message = new SimpleMailMessage();
    if (!from.isBlank()) {
      message.setFrom(from);
    }
    message.setTo(to);
    message.setSubject("[RePlant] 가입 계정 안내");
    message.setText("이 이메일로 가입된 RePlant 계정이 있습니다.\n\n" + guide
        + "\n본인이 요청하지 않았다면 이 메일을 무시해 주세요.\n");
    mailSender.send(message);
  }
}
