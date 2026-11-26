package com.justcommit.backend.member.auth.presentation.oauth2;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.application.LoginResult;
import com.justcommit.backend.member.auth.application.SocialLoginCommand;
import com.justcommit.backend.member.auth.application.SocialLoginResult;
import com.justcommit.backend.member.auth.application.SocialLoginService;
import com.justcommit.backend.member.auth.presentation.support.RefreshTokenCookieFactory;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

// 카카오 콜백에서 신규 가입 판정이 재가입 제한에 걸리면 프론트 콜백 주소에 오류 코드를 쿼리로 전달 (#611)
// 기존 회원은 Refresh 쿠키와 함께 콜백 주소로, 탈퇴가 보류됐던 회원이 복구됐을 때만 안내 표시를 붙임 (#616)
@ExtendWith(MockitoExtension.class)
class SocialLoginSuccessHandlerTest {

  private static final String FRONTEND = "http://localhost:3000";

  @Mock
  private SocialLoginService socialLoginService;

  @Mock
  private RefreshTokenCookieFactory refreshTokenCookieFactory;

  @Test
  @DisplayName("재가입 제한이면 /oauth/callback?error=REJOIN_RESTRICTED_EMAIL로 리다이렉트, 가입 토큰 · Refresh 쿠키 없음")
  void rejoinRestrictedRedirectsWithError() throws Exception {
    SocialLoginSuccessHandler handler = new SocialLoginSuccessHandler(
        socialLoginService, refreshTokenCookieFactory, new SocialLoginRedirectUrl(FRONTEND));
    given(socialLoginService.login(new SocialLoginCommand(Provider.KAKAO, "123", "user@kakao.com")))
        .willThrow(new BusinessException(MemberErrorCode.REJOIN_RESTRICTED_EMAIL));
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, kakaoAuthentication());

    assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/oauth/callback?error=REJOIN_RESTRICTED_EMAIL");
    assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    then(refreshTokenCookieFactory).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("탈퇴가 보류됐던 회원이 복구되면 /oauth/callback?notice=WITHDRAWAL_HELD, Refresh 쿠키는 평소와 같음 (#616)")
  void withdrawalHeldRestoredRedirectsWithNotice() throws Exception {
    MockHttpServletResponse response = loginExistingMember(true);

    assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/oauth/callback?notice=WITHDRAWAL_HELD");
    assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isEqualTo("refreshToken=refresh-token");
  }

  @Test
  @DisplayName("평소 기존 회원 로그인은 지금처럼 /oauth/callback, 안내 표시 없음 (#616)")
  void existingMemberRedirectsWithoutNotice() throws Exception {
    MockHttpServletResponse response = loginExistingMember(false);

    assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/oauth/callback");
    assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isEqualTo("refreshToken=refresh-token");
  }

  @Test
  @DisplayName("신규 회원은 지금처럼 /oauth/callback#signupToken=..., Refresh 쿠키 없음 (#616 영향 없음)")
  void newMemberRedirectsWithSignupToken() throws Exception {
    SocialLoginSuccessHandler handler = new SocialLoginSuccessHandler(
        socialLoginService, refreshTokenCookieFactory, new SocialLoginRedirectUrl(FRONTEND));
    given(socialLoginService.login(new SocialLoginCommand(Provider.KAKAO, "123", "user@kakao.com")))
        .willReturn(new SocialLoginResult.NewMember("signup-token", 600));
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, kakaoAuthentication());

    assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/oauth/callback#signupToken=signup-token");
    assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
  }

  private MockHttpServletResponse loginExistingMember(boolean withdrawalHeldRestored) throws Exception {
    SocialLoginSuccessHandler handler = new SocialLoginSuccessHandler(
        socialLoginService, refreshTokenCookieFactory, new SocialLoginRedirectUrl(FRONTEND));
    LoginResult login = new LoginResult(4L, "access-token", 1800, "닉네임", "MEMBER",
        "refresh-token", Duration.ofDays(1));
    given(socialLoginService.login(new SocialLoginCommand(Provider.KAKAO, "123", "user@kakao.com")))
        .willReturn(new SocialLoginResult.ExistingMember(login, withdrawalHeldRestored));
    given(refreshTokenCookieFactory.create("refresh-token", Duration.ofDays(1)))
        .willReturn(ResponseCookie.from("refreshToken", "refresh-token").build());
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, kakaoAuthentication());
    return response;
  }

  private static OAuth2AuthenticationToken kakaoAuthentication() {
    Map<String, Object> attributes = Map.of(
        "id", 123L,
        "kakao_account", Map.of("email", "user@kakao.com", "is_email_valid", true, "is_email_verified", true));
    DefaultOAuth2User user = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), attributes, "id");
    return new OAuth2AuthenticationToken(user, user.getAuthorities(), "kakao");
  }
}
