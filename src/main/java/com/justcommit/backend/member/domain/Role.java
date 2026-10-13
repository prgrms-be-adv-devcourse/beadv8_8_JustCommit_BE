package com.justcommit.backend.member.domain;

// 회원 권한 (ADMIN > SELLER > MEMBER)
// SYSTEM: 결제·정산용 내부 계정(홀딩·매출). 권한 계층과 무관하며 로그인 불가
// TODO: hasRole("SELLER") 등 계층이 필요한 규칙이 생기면 RoleHierarchy 설정 (ADMIN > SELLER > MEMBER, SYSTEM 제외)
public enum Role {
  MEMBER,
  SELLER,
  ADMIN,
  SYSTEM
}
