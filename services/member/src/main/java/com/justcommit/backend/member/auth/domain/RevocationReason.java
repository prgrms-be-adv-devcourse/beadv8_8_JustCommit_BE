package com.justcommit.backend.member.auth.domain;

// 토큰을 폐기한 이유 (#409)
public enum RevocationReason {
  PASSWORD_CHANGED,
  PASSWORD_RESET, // 비밀번호 재설정 (#412). 변경과 구분해 기록
  WITHDRAWN
}
