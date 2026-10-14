package com.justcommit.backend.member.auth.application;

// 소셜 로그인 결과 기존 회원이면 로그인, 신규 회원이면 추가정보 입력용 가입 토큰
public sealed interface SocialLoginResult {

  // 기존 회원: 일반 로그인과 같은 결과(Access는 Body, Refresh는 쿠키)
  record ExistingMember(LoginResult loginResult) implements SocialLoginResult {

  }

  // 신규 회원: POST /api/v1/users/social에 쓸 가입 토큰
  record NewMember(String signupToken, long expiresIn) implements SocialLoginResult {
    @Override
    public String toString() {
      return "NewMember[expiresIn=" + expiresIn + "]";
    }
  }
}
