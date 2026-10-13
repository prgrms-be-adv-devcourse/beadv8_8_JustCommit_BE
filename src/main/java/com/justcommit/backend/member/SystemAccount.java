package com.justcommit.backend.member;

// 결제·정산용 시스템 계정
// TODO: 최종에서 시스템 계정 값 정리 (이메일 도메인, 닉네임, 임시 휴대폰·계좌 값). 회원 테이블 대신 별도 구조가 나은지도 검토
public enum SystemAccount {

  HOLDING("system-holding@replant.com", "시스템홀딩", "00000000001"), // 구매 확정 전 결제 금액 보관
  REVENUE("system-revenue@replant.com", "시스템매출", "00000000002"); // 거래 완료 후 서비스 수수료 매출

  private final String email;
  private final String nickname;
  private final String phone;

  SystemAccount(String email, String nickname, String phone) {
    this.email = email;
    this.nickname = nickname;
    this.phone = phone;
  }

  public String email() {
    return email;
  }

  public String nickname() {
    return nickname;
  }

  public String phone() {
    return phone;
  }
}
