package com.justcommit.backend.member.presentation.oauth2;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.member.auth.application.LoginResult;
import com.justcommit.backend.member.auth.application.SocialLoginCommand;
import com.justcommit.backend.member.auth.application.SocialLoginResult;
import com.justcommit.backend.member.auth.application.SocialLoginService;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.auth.infrastructure.oauth2.KakaoUserInfo;
import com.justcommit.backend.member.presentation.dto.SocialLoginResponse;
import com.justcommit.backend.member.presentation.support.RefreshTokenCookieFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

// 소셜 로그인 성공(카카오 인증 완료) 후 처리
// 프론트가 없는 동안 리다이렉트 대신 JSON으로 응답(기존 회원: 로그인 / 신규 회원: 가입 토큰)
// common의 SecurityConfig가 AuthenticationSuccessHandler 타입으로 찾아서 연결
@Component@RequiredArgsConstructor
public class SocialLoginSuccessHandler implements AuthenticationSuccessHandler {

  private static final String KAKAO ="kakao";

  private final SocialLoginService socialLoginService;
  private final RefreshTokenCookieFactory refreshTokenCookieFactory;

  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                      Authentication authentication) throws IOException {
    try {
      SocialLoginResult result = socialLoginService.login(toCommand(authentication));

      //두 경우를 모두 처리하지 않으면 컴파일 에러
      switch (result) {
        case SocialLoginResult.ExistingMember existing -> {
          LoginResult login = existing.loginResult();

          response.addHeader(HttpHeaders.SET_COOKIE,
                  refreshTokenCookieFactory.create(login.refreshToken(), login.refreshTokenTtl()).toString());
          OAuth2JsonResponseWriter.write(response, HttpStatus.OK,
                  ApiResponse.ok("로그인되었습니다.", SocialLoginResponse.existingMember(login)));
        }
        case SocialLoginResult.NewMember newMember -> OAuth2JsonResponseWriter.write(response, HttpStatus.OK,
                ApiResponse.ok("추가 정보 입력이 필요합니다.", SocialLoginResponse.newMember(newMember)));
      }
    } catch (BusinessException e) {
      OAuth2JsonResponseWriter.writeError(response, e.getErrorCode());
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
