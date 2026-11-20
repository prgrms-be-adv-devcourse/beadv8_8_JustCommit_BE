package com.justcommit.backend.member.unlink.infrastructure.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// 카카오 연결 해제 안내 메일 문구 (#422): 실제로 보내지 않고 보낼 내용만 확인한다
class KakaoUnlinkNoticeMailSenderTest {

  @Test
  @DisplayName("[#422] 가입 이메일로 연결 해제 안내: 이용 불가 · 자동 탈퇴 안 됨 · 카카오 재로그인 · 탈퇴 방법 안내")
  void send() {
    JavaMailSender javaMailSender = mock(JavaMailSender.class);
    KakaoUnlinkNoticeMailSender sender = new KakaoUnlinkNoticeMailSender(javaMailSender, "noreply@replant.test");

    sender.send("user@example.com");

    ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(javaMailSender).send(message.capture());
    assertThat(message.getValue().getFrom()).isEqualTo("noreply@replant.test");
    assertThat(message.getValue().getTo()).containsExactly("user@example.com");
    assertThat(message.getValue().getSubject()).isEqualTo("[RePlant] 카카오 연결 해제 안내");
    assertThat(message.getValue().getText())
        .contains("이용할 수 없는 상태", "자동으로 진행하지 않았습니다", "카카오로 로그인", "탈퇴해 주세요");
  }
}
