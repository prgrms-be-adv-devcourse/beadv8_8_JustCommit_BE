package com.justcommit.backend.member;

// 다른 모듈(결제·정산·주문 등)에 공개하는 회원 조회 API
public interface MemberQuery {

  // 결제·정산용 계좌 (복호화된 계좌번호 포함). 회원이 없으면 MEMBER_NOT_FOUND(404)
  MemberAccount getAccount(Long memberId);
}
