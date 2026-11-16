package com.justcommit.backend.member.auth.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 이메일 인증 목적 (#412). 목적마다 인증번호 · 틀린 횟수 · 인증 완료를 따로 저장해,
// 다른 목적으로 받은 인증을 쓰지 못하게 한다 (예: 가입 인증으로 비밀번호 재설정 불가)
@Getter
@RequiredArgsConstructor
public enum EmailPurpose {

  SIGNUP("signup"),
  PASSWORD_RESET("password-reset"),
  ACCOUNT_CHANGE("account-change"); // 계좌 변경 (#413에서 연결)

  // Redis 키에 들어가는 이름 (예: auth:email:password-reset:{email})
  private final String keySegment;
}
