package com.justcommit.backend.member.presentation.dto;

import com.justcommit.backend.member.application.SignupResult;

// 회원가입 응답
public record SignupResponse(
        Long memberId,
        String nickname
) {
  public static SignupResponse from(SignupResult result) {
    return new SignupResponse(result.memberId(), result.nickname());
  }
}
