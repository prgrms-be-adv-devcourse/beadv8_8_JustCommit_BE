package com.justcommit.backend.member.presentation.dto;

// 회원 요청 DTO에서 함께 쓰는 입력 규칙
public final class MemberValidation {

  // @ 앞뒤에 공백, @ 없이 글자만 있고, 도메인에 점(.)이 최소 하나 (예: .com 없는 주소 차단)
  public static final String EMAIL_REGEX = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

  private MemberValidation() {
  }
}
