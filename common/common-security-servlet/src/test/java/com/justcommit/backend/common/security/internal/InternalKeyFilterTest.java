package com.justcommit.backend.common.security.internal;

import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.common.security.JwtVerifier;
import com.justcommit.backend.common.security.TokenException;
import com.justcommit.backend.common.security.jwt.JwtAuthenticationFilter;
import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class InternalKeyFilterTest {

  private static final String KEY = "test-internal-key-0123456789";
  private static final String INTERNAL_PATH = "/internal/v1/settlements/sellerOrders/1";

  private final JwtVerifier jwtVerifier = mock(JwtVerifier.class);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest(name = "서버 키 설정: [{0}]")
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "${INTERNAL_API_KEY}"})
  @DisplayName("서버 키 설정이 없거나 무효면 맞는 키를 보내도 401 INVALID_INTERNAL_KEY")
  void invalidServerKey(String configuredKey) throws Exception {
    Result result = runWithJwtFilter(configuredKey, internalRequest(KEY, null));

    assertRejected(result);
  }

  @Test
  @DisplayName("요청에 X-Internal-Key가 없으면 401 INVALID_INTERNAL_KEY")
  void noKeyHeader() throws Exception {
    Result result = runWithJwtFilter(KEY, internalRequest(null, null));

    assertRejected(result);
  }

  @Test
  @DisplayName("키가 틀리면 유효한 회원 토큰이 있어도 401 INVALID_INTERNAL_KEY (키가 먼저)")
  void wrongKeyEvenWithValidToken() throws Exception {
    given(jwtVerifier.parseAccessToken("good")).willReturn(new AuthMember(4L, "SELLER"));

    Result result = runWithJwtFilter(KEY, internalRequest("wrong-key", "Bearer good"));

    assertRejected(result);
  }

  @Test
  @DisplayName("키가 맞고 토큰이 없으면 내부 서비스 인증으로 통과한다 (스케줄러 · Kafka 리스너 호출)")
  void validKeyWithoutToken() throws Exception {
    Result result = runWithJwtFilter(KEY, internalRequest(KEY, null));

    assertThat(result.reachedEnd()).isTrue();
    assertThat(result.authentication()).isInstanceOf(InternalServiceAuthentication.class);
    assertThat(result.authentication().getPrincipal()).isNotInstanceOf(AuthMember.class);
  }

  @Test
  @DisplayName("키가 맞고 유효한 회원 토큰이 있으면 AuthMember를 그대로 유지한다")
  void validKeyWithValidToken() throws Exception {
    given(jwtVerifier.parseAccessToken("good")).willReturn(new AuthMember(4L, "SELLER"));

    Result result = runWithJwtFilter(KEY, internalRequest(KEY, "Bearer good"));

    assertThat(result.reachedEnd()).isTrue();
    assertThat(result.authentication().getPrincipal()).isEqualTo(new AuthMember(4L, "SELLER"));
  }

  @Test
  @DisplayName("키가 맞고 회원 토큰이 만료됐으면 회원 정보 없이 내부 서비스 인증으로 통과한다")
  void validKeyWithExpiredToken() throws Exception {
    given(jwtVerifier.parseAccessToken("expired"))
            .willThrow(new TokenException(TokenException.Reason.EXPIRED));

    Result result = runWithJwtFilter(KEY, internalRequest(KEY, "Bearer expired"));

    assertThat(result.reachedEnd()).isTrue();
    assertThat(result.authentication()).isInstanceOf(InternalServiceAuthentication.class);
  }

  @Test
  @DisplayName("/internal/**이 아닌 경로는 키 없이도 필터를 건너뛴다")
  void nonInternalPathIsSkipped() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");

    Result result = runWithJwtFilter(KEY, request);

    assertThat(result.reachedEnd()).isTrue();
    assertThat(result.response().getStatus()).isEqualTo(200);
    assertThat(result.authentication()).isNull();
  }

  @Test
  @DisplayName("거부 응답에는 서버 키나 요청한 키 값이 들어가지 않는다")
  void rejectionDoesNotLeakKey() throws Exception {
    Result result = runWithJwtFilter(KEY, internalRequest("wrong-key", null));

    String body = result.response().getContentAsString();
    assertThat(body).contains("INVALID_INTERNAL_KEY");
    assertThat(body).doesNotContain(KEY).doesNotContain("wrong-key");
  }

  // JWT 필터 → 내부 키 필터 → 마지막 지점 순서로 실행하고, 마지막 지점의 인증 정보를 기록
  private Result runWithJwtFilter(String configuredKey, MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    CapturingServlet end = new CapturingServlet();
    MockFilterChain chain = new MockFilterChain(end,
            new JwtAuthenticationFilter(jwtVerifier),
            new InternalKeyFilter(configuredKey));

    chain.doFilter(request, response);

    return new Result(response, end.called, end.authentication);
  }

  private static MockHttpServletRequest internalRequest(String key, String authorization) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", INTERNAL_PATH);
    if (key != null) {
      request.addHeader(InternalKeyFilter.HEADER, key);
    }
    if (authorization != null) {
      request.addHeader("Authorization", authorization);
    }
    return request;
  }

  private static void assertRejected(Result result) throws Exception {
    assertThat(result.reachedEnd()).isFalse();
    assertThat(result.response().getStatus()).isEqualTo(401);
    assertThat(result.response().getContentAsString()).contains("\"code\":\"INVALID_INTERNAL_KEY\"");
  }

  private record Result(MockHttpServletResponse response, boolean reachedEnd, Authentication authentication) {
  }

  // 필터를 모두 통과했을 때 도착하는 지점: 그 순간의 인증 정보를 기록
  private static final class CapturingServlet extends GenericServlet {
    private boolean called;
    private Authentication authentication;

    @Override
    public void service(ServletRequest request, ServletResponse response) {
      called = true;
      authentication = SecurityContextHolder.getContext().getAuthentication();
    }
  }
}
