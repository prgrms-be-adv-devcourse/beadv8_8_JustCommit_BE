package com.justcommit.backend.member.withdrawal.presentation.dto;

import com.justcommit.backend.member.withdrawal.application.WithdrawalTokenResult;

// 탈퇴 토큰 발급 응답 (expiresIn: 토큰 남은 시간, 초). 토큰은 최종 탈퇴 요청 본문으로 보낸다
public record WithdrawalTokenResponse(String withdrawalToken, long expiresIn) {

  public static WithdrawalTokenResponse from(WithdrawalTokenResult result) {
    return new WithdrawalTokenResponse(result.withdrawalToken(), result.expiresIn());
  }
}
