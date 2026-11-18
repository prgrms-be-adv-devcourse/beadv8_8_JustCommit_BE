package com.justcommit.backend.member.withdrawal.presentation.dto;

import com.justcommit.backend.member.withdrawal.application.WithdrawalResult;

// 탈퇴 응답. revocationCompleted: 서버의 토큰 폐기 기록 완료 여부 (false = 탈퇴는 됐고 다른 기기 로그아웃 반영만 늦어질 수 있음)
public record WithdrawalResponse(boolean revocationCompleted) {

  public static WithdrawalResponse from(WithdrawalResult result) {
    return new WithdrawalResponse(result.revocationCompleted());
  }
}
