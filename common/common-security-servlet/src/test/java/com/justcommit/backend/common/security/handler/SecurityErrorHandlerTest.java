package com.justcommit.backend.common.security.handler;

import com.justcommit.backend.common.security.TokenException;
import com.justcommit.backend.common.security.jwt.JwtAuthenticationFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityErrorHandlerTest {

  private final JwtAuthenticationEntryPoint entryPoint = new JwtAuthenticationEntryPoint();
  private final JwtAccessDeniedHandler accessDeniedHandler = new JwtAccessDeniedHandler();

  @Test
  @DisplayName("토큰 없이 인증이 필요한 경로에 오면 401 UNAUTHORIZED")
  void noToken() throws Exception {
    MockHttpServletResponse response = commence(null);

    assertError(response, 401, "UNAUTHORIZED");
  }

  @Test
  @DisplayName("만료된 토큰이면 401 TOKEN_EXPIRED")
  void expiredToken() throws Exception {
    MockHttpServletResponse response = commence(TokenException.Reason.EXPIRED);

    assertError(response, 401, "TOKEN_EXPIRED");
  }

  @Test
  @DisplayName("잘못된 토큰이면 401 INVALID_TOKEN")
  void invalidToken() throws Exception {
    MockHttpServletResponse response = commence(TokenException.Reason.INVALID);

    assertError(response, 401, "INVALID_TOKEN");
  }

  @Test
  @DisplayName("로그인했지만 권한이 없으면 403 FORBIDDEN")
  void forbidden() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    accessDeniedHandler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

    assertError(response, 403, "FORBIDDEN");
  }

  // 필터가 request에 남긴 실패 이유를 흉내 내고 401 핸들러를 부른다
  private MockHttpServletResponse commence(TokenException.Reason reason) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (reason != null) {
      request.setAttribute(JwtAuthenticationFilter.TOKEN_ERROR_ATTRIBUTE, reason);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    entryPoint.commence(request, response, new InsufficientAuthenticationException("no auth"));
    return response;
  }

  // ApiResponse 형식({status, code, message})의 JSON으로 내려가는지 확인
  private static void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
    assertThat(response.getStatus()).isEqualTo(status);
    assertThat(response.getContentType()).startsWith("application/json");
    assertThat(response.getContentAsString()).contains("\"code\":\"" + code + "\"");
  }
}
