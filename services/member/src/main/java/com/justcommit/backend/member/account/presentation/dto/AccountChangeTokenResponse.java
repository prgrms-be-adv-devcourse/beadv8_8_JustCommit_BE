package com.justcommit.backend.member.account.presentation.dto;

import com.justcommit.backend.member.account.application.AccountChangeTokenResult;

// 계좌 변경 토큰 발급 응답 (expiresIn: 토큰 남은 시간, 초). 토큰은 계좌 변경 요청 본문으로 보낸다
public record AccountChangeTokenResponse(String accountChangeToken, long expiresIn) {

  public static AccountChangeTokenResponse from(AccountChangeTokenResult result) {
    return new AccountChangeTokenResponse(result.accountChangeToken(), result.expiresIn());
  }
}
