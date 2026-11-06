package com.justcommit.backend.common.security.oauth2;

import com.justcommit.backend.common.security.jwt.JwtProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

import java.time.Duration;
import java.util.Set;

import static com.justcommit.backend.common.security.oauth2.CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

class CookieOAuth2AuthorizationRequestRepositoryTest {

  private static final String SECRET = "test-only-jwt-secret-key-for-automated-tests-01234";
  private static final String OTHER_SECRET = "another-secret-key-used-to-simulate-forgery-56789";

  private final CookieOAuth2AuthorizationRequestRepository repository = createRepository(SECRET, false);

  private static CookieOAuth2AuthorizationRequestRepository createRepository(String secret, boolean secure) {
    JwtProperties properties = new JwtProperties(secret, Duration.ofMinutes(30), Duration.ofMinutes(10));
    return new CookieOAuth2AuthorizationRequestRepository(properties, secure);
  }

  private static OAuth2AuthorizationRequest authorizationRequest() {
    return OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://kauth.kakao.com/oauth/authorize")
            .clientId("test-client")
            .redirectUri("http://localhost:8080/login/oauth2/code/kakao")
            .scopes(Set.of("account_email"))
            .state("test-state")
            .attributes(attrs -> attrs.put(OAuth2ParameterNames.REGISTRATION_ID, "kakao"))
            .build();
  }

  // 저장 후 응답의 Set-Cookie 헤더 전체
  private String saveAndGetSetCookie(CookieOAuth2AuthorizationRequestRepository repo) {
    MockHttpServletResponse response = new MockHttpServletResponse();
    repo.saveAuthorizationRequest(authorizationRequest(), new MockHttpServletRequest(), response);
    return response.getHeader(HttpHeaders.SET_COOKIE);
  }

  // Set-Cookie 헤더에서 쿠키 값만 꺼냄 ("이름=값; Path=..." → 값)
  private static String cookieValue(String setCookie) {
    return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
  }

  private static MockHttpServletRequest requestWithCookie(String value) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(COOKIE_NAME, value));
    return request;
  }

  @Test
  @DisplayName("저장한 인가 요청을 쿠키로 다시 꺼낼 수 있음")
  void saveAndLoad() {
    String value = cookieValue(saveAndGetSetCookie(repository));

    OAuth2AuthorizationRequest loaded = repository.loadAuthorizationRequest(requestWithCookie(value));

    assertThat(loaded).isNotNull();
    assertThat(loaded.getState()).isEqualTo("test-state");
    assertThat(loaded.getRedirectUri()).isEqualTo("http://localhost:8080/login/oauth2/code/kakao");
  }

  @Test
  @DisplayName("저장 쿠키는 HttpOnly, SameSite=Lax, Path=/, 5분이고 secure=false면 Secure가 없음")
  void cookieAttributes() {
    String setCookie = saveAndGetSetCookie(repository);

    assertThat(setCookie)
            .startsWith(COOKIE_NAME + "=")
            .contains("Path=/", "Max-Age=300", "HttpOnly", "SameSite=Lax")
            .doesNotContain("Secure");
  }

  @Test
  @DisplayName("secure=true면 쿠키에 Secure가 붙음")
  void secureCookie() {
    String setCookie = saveAndGetSetCookie(createRepository(SECRET, true));

    assertThat(setCookie).contains("Secure");
  }

  @Test
  @DisplayName("쿠키가 없으면 null")
  void noCookie() {
    assertThat(repository.loadAuthorizationRequest(new MockHttpServletRequest())).isNull();
  }

  @Test
  @DisplayName("쿠키 내용을 조작하면 서명 검증에 실패해 null")
  void tamperedPayload() {
    String value = cookieValue(saveAndGetSetCookie(repository));
    int dot = value.lastIndexOf('.');
    String tampered = value.substring(0, dot) + "AAAA" + value.substring(dot);

    assertThat(repository.loadAuthorizationRequest(requestWithCookie(tampered))).isNull();
  }

  @Test
  @DisplayName("다른 키로 서명한 쿠키는 null")
  void signedWithOtherKey() {
    String forged = cookieValue(saveAndGetSetCookie(createRepository(OTHER_SECRET, false)));

    assertThat(repository.loadAuthorizationRequest(requestWithCookie(forged))).isNull();
  }

  @Test
  @DisplayName("형식이 맞지 않는 쿠키 값은 null")
  void malformedCookie() {
    assertThat(repository.loadAuthorizationRequest(requestWithCookie("not-a-valid-value"))).isNull();
  }

  @Test
  @DisplayName("remove는 인가 요청을 돌려주고 쿠키를 만료시킴")
  void removeDeletesCookie() {
    String value = cookieValue(saveAndGetSetCookie(repository));
    MockHttpServletResponse response = new MockHttpServletResponse();

    OAuth2AuthorizationRequest removed =
            repository.removeAuthorizationRequest(requestWithCookie(value), response);

    assertThat(removed.getState()).isEqualTo("test-state");
    assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
  }
}
