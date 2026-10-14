package com.justcommit.backend.member;

// 다른 모듈(결제·정산·주문 등)에 공개하는 회원 조회 API
public interface MemberQuery {

  // 결제·정산용 계좌 (복호화된 계좌번호 포함). 회원이 없으면 MEMBER_NOT_FOUND(404)
  MemberAccount getAccount(Long memberId);

  // 주문용 배송지. 없거나 본인 배송지가 아니면 ADDRESS_NOT_FOUND(404)
  MemberAddress getAddress(Long memberId, Long addressId);

  // 주문용 기본 배송지. 없으면 ADDRESS_NOT_FOUND(404)
  MemberAddress getDefaultAddress(Long memberId);

  // 결제·정산용 시스템 계정 ID. 앱 시작 시 자동 생성되므로 없으면 서버 설정 문제
  Long getHoldingMemberId();
  Long getRevenueMemberId();

  // 판매자로 등록된 회원인지 (회원이 없으면 false)
  boolean isSeller(Long memberId);

  // 판매자 닉네임. 회원이 없으면 MEMBER_NOT_FOUND(404)
  String getNickname(Long memberId);
}
