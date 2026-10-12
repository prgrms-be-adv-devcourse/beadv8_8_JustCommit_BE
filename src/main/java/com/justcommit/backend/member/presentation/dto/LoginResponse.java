package com.justcommit.backend.member.presentation.dto;

import com.justcommit.backend.member.application.LoginResult;

public record LoginResponse(
        String accessToken,
        long expiresIn,
        String nickname,
        String role
) {
  public static LoginResponse from(LoginResult result) {
    return new LoginResponse(result.accessToken(), result.expiresIn(), result.nickname(), result.role());
  }
}
