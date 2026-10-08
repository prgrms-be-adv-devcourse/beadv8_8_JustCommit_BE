package com.justcommit.backend.common.security.handler;

import com.justcommit.backend.common.exception.ErrorCode;
import com.justcommit.backend.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// Security 단계(컨트롤러 이전)의 에러를 JSON으로 응답
// 필터 단계라 @RestControllerAdvice가 동작하지 않아서 직접 응답을 씀
// 응답 형식은 팀 공통 ApiResponse와 동일하게 맞춤
final class ErrorResponseWriter {

  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

  private ErrorResponseWriter() {
  }

  static void write(HttpServletResponse response, ErrorCode errorCode)
          throws IOException {
    response.setStatus(errorCode.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(JSON_MAPPER.writeValueAsString(ApiResponse.error(errorCode)));
  }
}
