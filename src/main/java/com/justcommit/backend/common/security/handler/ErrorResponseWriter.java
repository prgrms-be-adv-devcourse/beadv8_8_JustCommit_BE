package com.justcommit.backend.common.security.handler;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// Security 단계(컨트롤러 이전)의 에러를 JSON으로 응답
// 필터 단계라 @RestControllerAdvice가 동작하지 않아서 직접 응답을 씀
final class ErrorResponseWriter {

  private ErrorResponseWriter() {
  }

  static void write(HttpServletResponse response, int status, String code, String message)
          throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    // code, message는 서버에서 정한 고정 문자열만 들어가므로 직접 조립해도 안전
    response.getWriter().write(
            "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}"
    );
  }
}
