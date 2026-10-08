package com.justcommit.backend.member.presentation.dto;

// 회원 요청 DTO에서 함께 쓰는 입력 규칙
public final class MemberValidation {

  // @ 앞뒤에 공백·@ 없는 글자가 1자 이상, 도메인에 점(.)이 최소 하나 (예: .com 없는 주소 차단)
  public static final String EMAIL_REGEX = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

  // 8~20자, 영문과 숫자를 각각 1개 이상 포함. 특수문자 !@#$%^&* 허용
  public static final String PASSWORD_REGEX = "^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d!@#$%^&*]{8,20}$";

  // 최소2, 최대8/ 한글·영문·숫자만(특수문자·공백 불가)
  public static final String NICKNAME_REGEX = "^[가-힣A-Za-z0-9]{2,8}$";

  // 하이픈 없이 01로 시작하는 숫자 11자리
  public static final String PHONE_REGEX = "^01\\d{9}$";

  private MemberValidation() {
  }
}
