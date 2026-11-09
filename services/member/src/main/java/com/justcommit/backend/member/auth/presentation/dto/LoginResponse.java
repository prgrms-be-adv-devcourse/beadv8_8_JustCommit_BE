package com.justcommit.backend.member.auth.presentation.dto;

import com.justcommit.backend.member.auth.application.LoginResult;

public record LoginResponse(
        Long memberId,
        String accessToken,
        long expiresIn,
        String nickname,
        String role
) {
  public static LoginResponse from(LoginResult result) {
    return new LoginResponse(result.memberId(), result.accessToken(), result.expiresIn(), result.nickname(), result.role());
  }
}
