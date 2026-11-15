package com.justcommit.backend.member.auth.domain;

// 토큰을 폐기한 이유 (#409)
public enum RevocationReason {
  PASSWORD_CHANGED,
  WITHDRAWN
}
