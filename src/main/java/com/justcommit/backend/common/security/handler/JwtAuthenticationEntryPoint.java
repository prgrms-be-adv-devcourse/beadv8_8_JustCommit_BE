package com.justcommit.backend.common.security.handler;

import com.justcommit.backend.common.security.TokenException;
import com.justcommit.backend.common.security.jwt.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

// 인증이 필요한 경로에 인증 없이 접근했을 때 401 응답
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

  @Override
  public void commence(HttpServletRequest request, HttpServletResponse response,
                       AuthenticationException authException) throws IOException {

    // 필터가 기록해 둔 실패 이유 (토큰이 없었으면 null)
    Object reason = request.getAttribute(JwtAuthenticationFilter.TOKEN_ERROR_ATTRIBUTE);

    if (reason == TokenException.Reason.EXPIRED) {
      ErrorResponseWriter.write(response, SecurityErrorCode.TOKEN_EXPIRED);
    } else if (reason == TokenException.Reason.INVALID) {
      ErrorResponseWriter.write(response, SecurityErrorCode.INVALID_TOKEN);
    } else {
      ErrorResponseWriter.write(response, SecurityErrorCode.UNAUTHORIZED);
    }
  }
}
