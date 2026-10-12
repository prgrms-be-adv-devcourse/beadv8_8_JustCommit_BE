package com.justcommit.backend.member.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.justcommit.backend.member.application.LoginResult;
import com.justcommit.backend.member.application.SocialLoginResult;

// 소셜 로그인 콜백 응답
// 기존 회원: 로그인 응답과 같은 필드 / 신규 회원: 추가정보 입력에 쓸 가입 토큰
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SocialLoginResponse(
        boolean newMember,
        String assessToken,
        Long expiresIn, // 기존 회원은 Access 토큰,신규 회원은 가입 토큰 유효 시간
        String nickname,
        String role,
        String signupToken
) {

  public static SocialLoginResponse existingMember(LoginResult result) {
    return new SocialLoginResponse(false, result.accessToken(), result.expiresIn(),
            result.nickname(), result.role(), null);
  }

  public static SocialLoginResponse newMember(SocialLoginResult.NewMember result) {
    return new SocialLoginResponse(true, null, result.expiresIn(), null, null, result.signupToken());
  }
}
