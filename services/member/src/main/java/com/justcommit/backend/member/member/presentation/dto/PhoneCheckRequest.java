package com.justcommit.backend.member.member.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import static com.justcommit.backend.member.presentation.validation.MemberValidation.PHONE_REGEX;

// 휴대폰 중복 확인 (?phone=01012345678): 가입 요청(SignupRequest)의 휴대폰과 같은 형식만 받는다
// 형식이 다르면 탈퇴 기록 · 회원을 조회하지 않고 400
public record PhoneCheckRequest(
  @NotBlank(message = "휴대폰 번호를 입력해주세요.")
  @Pattern(regexp = PHONE_REGEX, message = "휴대폰 번호는 하이픈 없이 숫자 11자리여야 합니다.")
  String phone
) {
}
