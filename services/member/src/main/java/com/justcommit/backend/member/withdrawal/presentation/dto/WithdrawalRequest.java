package com.justcommit.backend.member.withdrawal.presentation.dto;

// 최종 탈퇴 요청 (#407). 로컬 회원은 password, 카카오 회원은 withdrawalToken 하나만 보낸다
// 어느 값이 필수인지는 회원 유형마다 달라 여기서 검증하지 않고 서비스가 확인한다 (다르면 400 BAD_REQUEST_DATA)
public record WithdrawalRequest(String password, String withdrawalToken) {

  // 로그 · 디버깅 출력에 비밀번호 · 탈퇴 토큰이 찍히지 않도록 가림
  @Override
  public String toString() {
    return "WithdrawalRequest[password=****, withdrawalToken=****]";
  }
}
