package com.justcommit.backend.member.presentation.dto;

import com.justcommit.backend.member.auth.application.TokenReissueResult;

// 재발급 응답 Body (Refresh 토큰은 쿠키로만 전달)
public record TokenReissueResponse(String accessToken, long expiresIn) {

  public static TokenReissueResponse from(TokenReissueResult result) {
    return new TokenReissueResponse(result.accessToken(), result.expiresIn());
  }

  @Override
  public String toString() {
    return "TokenReissueResponse[accessToken=***, expiresIn=" + expiresIn + "]";
  }
}
