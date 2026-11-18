package com.justcommit.backend.member.member.presentation.dto;

import com.justcommit.backend.member.member.application.PasswordChangeResult;

// 비밀번호 변경 응답. revocationCompleted는 서버의 토큰 폐기 기록 완료 여부로만 해석
public record PasswordChangeResponse(boolean revocationCompleted) {

  public static PasswordChangeResponse from(PasswordChangeResult result) {
    return new PasswordChangeResponse(result.revocationCompleted());
  }
}
