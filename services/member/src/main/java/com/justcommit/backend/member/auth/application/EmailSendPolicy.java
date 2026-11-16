package com.justcommit.backend.member.auth.application;

import java.time.Duration;

// 같은 이메일로 보내는 메일의 발송 예약 제한 (#412). 인증 메일과 가입 계정 안내 메일이 함께 쓴다
// 같은 이메일로 발송 예약은 60초에 한 번, 첫 예약부터 24시간 동안 최대 5회.
// 메일을 보내지 않고 끝난 요청(안내 대상이 아님 등)도 예약에 포함하고, SMTP 실패 시에만 그 요청의 예약을 되돌린다
public final class EmailSendPolicy {

  public static final Duration COOLDOWN = Duration.ofSeconds(60);
  public static final int DAILY_LIMIT = 5;
  public static final Duration DAILY_WINDOW = Duration.ofDays(1);

  private EmailSendPolicy() {
  }
}
