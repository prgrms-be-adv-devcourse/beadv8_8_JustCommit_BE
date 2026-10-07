package com.justcommit.backend.common.security.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

// 로그인은 했지만 권한이 부족할 때 403 응답 (예: MEMBER가 판매 API 호출)
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

  @Override
  public void handle(HttpServletRequest request, HttpServletResponse response,
                     AccessDeniedException accessDeniedException) throws IOException {
    ErrorResponseWriter.write(response, HttpServletResponse.SC_FORBIDDEN,
            "FORBIDDEN", "접근 권한이 없습니다.");
  }
}
