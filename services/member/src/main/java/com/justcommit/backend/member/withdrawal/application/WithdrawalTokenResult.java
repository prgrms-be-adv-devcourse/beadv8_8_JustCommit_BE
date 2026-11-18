package com.justcommit.backend.member.withdrawal.application;

// 탈퇴 토큰 발급 결과 (expiresIn: 초)
public record WithdrawalTokenResult(String withdrawalToken, long expiresIn) {

  // 로그 · 디버깅 출력에 토큰 원문이 찍히지 않도록 가림
  @Override
  public String toString() {
    return "WithdrawalTokenResult[withdrawalToken=****, expiresIn=" + expiresIn + "]";
  }
}
