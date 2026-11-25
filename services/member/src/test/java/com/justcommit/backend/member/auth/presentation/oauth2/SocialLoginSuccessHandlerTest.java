package com.justcommit.backend.member.auth.presentation.oauth2;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.application.SocialLoginCommand;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

// 카카오 콜백에서 신규 가입 판정이 재가입 제한에 걸리면 프론트 콜백 주소에 오류 코드를 쿼리로 전달 (#611)
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

  private static OAuth2AuthenticationToken kakaoAuthentication() {
    Map<String, Object> attributes = Map.of(
        "id", 123L,
        "kakao_account", Map.of("email", "user@kakao.com", "is_email_valid", true, "is_email_verified", true));
    DefaultOAuth2User user = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), attributes, "id");
    return new OAuth2AuthenticationToken(user, user.getAuthorities(), "kakao");
  }
}
