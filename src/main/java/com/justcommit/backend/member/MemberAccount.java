package com.justcommit.backend.member;

// 회원 계좌 정보 (결제·정산 모듈로 전달)
public record MemberAccount(
        String bankCode,
        String accountNo,      // 복호화된 원문 계좌번호
        String accountHolder
) {
  // 로그·디버깅 출력에 원문 계좌번호가 찍히지 않도록 뒤 4자리만 노출
  @Override
  public String toString() {
    return "MemberAccount[bankCode=" + bankCode
            + ", accountNo=****" + accountNo.substring(accountNo.length() - 4)
            + ", accountHolder=" + accountHolder + "]";
  }
}
