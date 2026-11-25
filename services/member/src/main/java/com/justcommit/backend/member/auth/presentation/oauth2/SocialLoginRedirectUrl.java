package com.justcommit.backend.member.auth.presentation.oauth2;

import com.justcommit.backend.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

// 소셜 로그인 후 프론트로 돌아갈 주소 생성
// 기존 회원: /oauth/callback (Refresh 쿠키만 설정, 프론트가 재발급으로 Access 토큰 받음)
//          탈퇴가 보류됐던 회원이 복구됐으면 /oauth/callback?notice=WITHDRAWAL_HELD (#616, 쿠키는 같음)
// 신규 회원: /oauth/callback#signupToken=... (# 뒤는 서버로 전송되지 않아 로그에 안 남음)
// 실패:     /oauth/callback?error=에러코드
@Component
class SocialLoginRedirectUrl {

  private static final String CALLBACK_PATH = "/oauth/callback";

  private final String frontendBaseUrl;

  SocialLoginRedirectUrl(@Value("${custom.frontend.base-url}") String frontendBaseUrl) {
    this.frontendBaseUrl = frontendBaseUrl;
  }

  String existingMember() {
    return callback().toUriString();
  }

  String withdrawalHeldRestored() {
    return callback().queryParam("notice", "WITHDRAWAL_HELD").toUriString();
  }

  String newMember(String signupToken) {
    return callback().fragment("signupToken=" + signupToken).toUriString();
  }

  String error(ErrorCode errorCode) {
    return callback().queryParam("error", errorCode.name()).toUriString();
  }

  private UriComponentsBuilder callback() {
    return UriComponentsBuilder.fromUriString(frontendBaseUrl).path(CALLBACK_PATH);
  }
}
