package com.justcommit.backend.member.presentation.oauth2;

import com.justcommit.backend.common.exception.ErrorCode;
import com.justcommit.backend.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// 소셜 로그인 핸들러의 JSON 응답 작성
// 핸들러는 Security 필터 단계에서 실행돼 Spring MVC의 JSON 변환·@RestControllerAdvice 동작x
final class OAuth2JsonResponseWriter {

  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

  private OAuth2JsonResponseWriter() {

  }

  static void write(HttpServletResponse response, HttpStatus status, ApiResponse<?> body)
          throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(JSON_MAPPER.writeValueAsString(body));
  }

  static void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
    write(response, errorCode.getStatus(), ApiResponse.error(errorCode));
  }
}
