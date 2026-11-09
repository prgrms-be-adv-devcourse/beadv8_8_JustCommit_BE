package com.justcommit.backend.common.security.handler;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

// 내부 API 키가 없거나 틀렸을 때 401 INVALID_INTERNAL_KEY 응답
// 응답에는 키 값이나 기대한 키에 대한 정보를 넣지 않음
public final class InternalKeyFailureHandler {

  private InternalKeyFailureHandler() {
  }

  public static void reject(HttpServletResponse response) throws IOException {
    ErrorResponseWriter.write(response, SecurityErrorCode.INVALID_INTERNAL_KEY);
  }
}
