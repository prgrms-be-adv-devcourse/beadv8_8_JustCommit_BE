package com.justcommit.backend.member.domain;

// 회원 권한 (ADMIN > SELLER > MEMBER)
// TODO: hasRole("SELLER") 등 계층이 필요한 규칙이 생기면 RoleHierarchy 설정 (ADMIN > SELLER > MEMBER)
public enum Role {
  MEMBER,
  SELLER,
  ADMIN
}
