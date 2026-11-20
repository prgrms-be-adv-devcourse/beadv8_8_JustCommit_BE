package com.justcommit.backend.member.unlink.infrastructure.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

// 카카오 연결 해제 안내 메일 (#422). 자동 탈퇴 조건(BLOCKED)에 걸려 INACTIVE로 남은 회원에게 가입 이메일로 보낸다
@Component
public class KakaoUnlinkNoticeMailSender {

  private final JavaMailSender mailSender;
  private final String from;

  public KakaoUnlinkNoticeMailSender(JavaMailSender mailSender,
                                     @Value("${spring.mail.username:}") String from) {
    this.mailSender = mailSender;
    this.from = from;
  }

  // 발송 실패 시 MailException(RuntimeException) → 처리 서비스가 재시도
  public void send(String to) {
    SimpleMailMessage message = new SimpleMailMessage();
    if (!from.isBlank()) {
      message.setFrom(from);
    }
    message.setTo(to);
    message.setSubject("[RePlant] 카카오 연결 해제 안내");
    message.setText("""
        카카오 계정과 RePlant의 연결이 해제되어 RePlant 계정을 이용할 수 없는 상태가 되었습니다.

        진행 중인 주문 · 판매 · 정산이나 남은 예치금이 있어 회원 탈퇴는 자동으로 진행하지 않았습니다.

        - 다시 이용하려면: 로그인 화면에서 '카카오로 로그인'으로 다시 연결해 주세요.
        - 탈퇴를 원하면: 카카오로 다시 로그인한 뒤 진행 중인 거래를 마치고 탈퇴해 주세요.

        본인이 카카오 연결을 해제하지 않았다면 카카오 계정의 보안 설정을 확인해 주세요.
        """);
    mailSender.send(message);
  }
}
