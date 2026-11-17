package com.justcommit.backend.member.account.application;

// 계좌 변경 요청 값 (형식은 요청 검증을 통과한 값)
public record AccountChangeCommand(String accountChangeToken, String bankCode, String accountNo, String accountHolder) {

  // 로그·디버깅 출력에 토큰 · 계좌번호 원문이 찍히지 않도록 가림
  @Override
  public String toString() {
    return "AccountChangeCommand[accountChangeToken=****, bankCode=" + bankCode
        + ", accountNo=****, accountHolder=" + accountHolder + "]";
  }
}
