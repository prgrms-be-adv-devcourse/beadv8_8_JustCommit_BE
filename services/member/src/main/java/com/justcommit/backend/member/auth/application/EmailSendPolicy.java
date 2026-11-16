package com.justcommit.backend.member.auth.application;

import java.time.Duration;

// 같은 이메일로 보내는 메일의 발송 제한 (#412). 인증 메일과 가입 계정 안내 메일이 함께 쓴다
// 어느 메일이든 같은 이메일로는 60초에 한 번, 첫 발송부터 24시간 동안 5번까지 (서버 쪽 발송 실패는 세지 않음)
public final class EmailSendPolicy {

  public static final Duration COOLDOWN = Duration.ofSeconds(60);
  public static final int DAILY_LIMIT = 5;
  public static final Duration DAILY_WINDOW = Duration.ofDays(1);

  private EmailSendPolicy() {
  }
}
