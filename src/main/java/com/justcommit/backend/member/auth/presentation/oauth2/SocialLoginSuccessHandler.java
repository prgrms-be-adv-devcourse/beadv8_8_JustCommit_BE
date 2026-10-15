package com.justcommit.backend.member.auth.presentation.oauth2;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.application.LoginResult;
import com.justcommit.backend.member.auth.application.SocialLoginCommand;
import com.justcommit.backend.member.auth.application.SocialLoginResult;
import com.justcommit.backend.member.auth.application.SocialLoginService;
import com.justcommit.backend.member.auth.infrastructure.oauth2.KakaoUserInfo;
import com.justcommit.backend.member.auth.presentation.support.RefreshTokenCookieFactory;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

// 소셜 로그인 성공(카카오 인증 완료) 후 처리
// 결과에 따라 프론트 콜백 주소로 리다이렉트(기존 회원: 로그인 쿠키 / 신규 회원: 가입 토큰)
// common의 SecurityConfig가 AuthenticationSuccessHandler 타입으로 찾아서 연결
@Component
@RequiredArgsConstructor
public class SocialLoginSuccessHandler implements AuthenticationSuccessHandler {

  private static final String KAKAO = "kakao";

  private final SocialLoginService socialLoginService;
  private final RefreshTokenCookieFactory refreshTokenCookieFactory;
  private final SocialLoginRedirectUrl redirectUrl;

  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                      Authentication authentication) throws IOException {
    try {
      SocialLoginResult result = socialLoginService.login(toCommand(authentication));

      // 두 경우를 모두 처리하지 않으면 컴파일 에러
      switch (result) {
        case SocialLoginResult.ExistingMember existing -> {
          LoginResult login = existing.loginResult();

          response.addHeader(HttpHeaders.SET_COOKIE,
                  refreshTokenCookieFactory.create(login.refreshToken(), login.refreshTokenTtl()).toString());
          response.sendRedirect(redirectUrl.existingMember());  // JSON 응답 대신 리다이렉트
        }
        case SocialLoginResult.NewMember newMember ->
                response.sendRedirect(redirectUrl.newMember(newMember.signupToken()));
      }
    } catch (BusinessException e) {
      response.sendRedirect(redirectUrl.error(e.getErrorCode()));
    }
  }

  // 카카오 사용자 정보 -> 서비스 요청
  private SocialLoginCommand toCommand(Authentication authentication) {
    if (!(authentication instanceof OAuth2AuthenticationToken token)
            || !KAKAO.equals(token.getAuthorizedClientRegistrationId())) {
      throw new BusinessException(MemberErrorCode.SOCIAL_LOGIN_FAILED);
    }
    KakaoUserInfo info = KakaoUserInfo.from(token.getPrincipal().getAttributes());
    return new SocialLoginCommand(Provider.KAKAO, info.providerId(), info.email());
  }
}
