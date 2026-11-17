package com.justcommit.backend.member.auth.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 하루 발송 한도를 따로 세는 단위 (#412 리뷰 반영).
// 하루 5회는 용도별로 세고, 60초 재발송 간격은 용도와 관계없이 이메일 단위로 공유한다
// (한 용도의 요청이 다른 용도의 하루 한도를 소진하지 않게. 예: 가입 계정 안내가 비밀번호 재설정 한도를 막지 않음)
@Getter
@RequiredArgsConstructor
public enum EmailSendQuota {

  SIGNUP("signup"),
  PASSWORD_RESET("password-reset"),
  ACCOUNT_CHANGE("account-change"),
  ACCOUNT_REMINDER("account-reminder"); // 가입 계정 안내 (인증번호 없는 메일이라 EmailPurpose에는 없음)

  // Redis 키에 들어가는 이름 (예: auth:email:daily:account-reminder:{email})
  private final String keySegment;

  // 인증 메일은 인증 목적과 같은 이름의 한도를 쓴다 (예약 · 복구가 같은 기준을 쓰도록 한 곳에서 정함)
  public static EmailSendQuota of(EmailPurpose purpose) {
    return switch (purpose) {
      case SIGNUP -> SIGNUP;
      case PASSWORD_RESET -> PASSWORD_RESET;
      case ACCOUNT_CHANGE -> ACCOUNT_CHANGE;
    };
  }
}
