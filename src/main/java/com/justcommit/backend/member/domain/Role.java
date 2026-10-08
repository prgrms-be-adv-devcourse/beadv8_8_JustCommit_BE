package com.justcommit.backend.member.domain;

// 회원 권한 (ADMIN > SELLER > MEMBER, 계층은 판매자 등록 이슈에서 RoleHierarchy로 설정)
public enum Role {
  MEMBER,
  SELLER,
  ADMIN
}
