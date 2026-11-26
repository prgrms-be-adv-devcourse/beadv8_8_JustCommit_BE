package com.justcommit.backend.member.auth.application;

// 소셜 로그인 결과 기존 회원이면 로그인, 신규 회원이면 추가정보 입력용 가입 토큰
public sealed interface SocialLoginResult {

  // 기존 회원: 일반 로그인과 같은 결과(Access는 Body, Refresh는 쿠키)
  // withdrawalHeldRestored: 카카오 연결 해제 뒤 탈퇴가 보류됐던 회원이 이번 로그인으로 복구됨 (#616, 프론트가 팝업으로 한 번 안내)
  record ExistingMember(LoginResult loginResult, boolean withdrawalHeldRestored) implements SocialLoginResult {

  }

  // 신규 회원: POST /api/v1/users/social에 쓸 가입 토큰
  record NewMember(String signupToken, long expiresIn) implements SocialLoginResult {
    @Override
    public String toString() {
      return "NewMember[expiresIn=" + expiresIn + "]";
    }
  }
}
