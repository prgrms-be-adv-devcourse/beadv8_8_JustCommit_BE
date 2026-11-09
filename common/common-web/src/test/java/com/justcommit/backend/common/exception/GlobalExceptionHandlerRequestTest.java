package com.justcommit.backend.common.exception;

import com.justcommit.backend.common.response.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerRequestTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  @DisplayName("필수 헤더가 없으면 500이 아니라 400 MISSING_HEADER이고 빠진 헤더 이름을 알려 준다")
  void returns400OnMissingHeader() throws Exception {
    MissingRequestHeaderException exception =
        new MissingRequestHeaderException("Idempotency-Key", headerParameter());

    ResponseEntity<ApiResponse<Void>> response = handler.handleMissingHeader(exception);

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody().code()).isEqualTo("MISSING_HEADER");
    assertThat(response.getBody().message()).contains("Idempotency-Key");
  }

  // 예외를 만들 때 필요한 "어느 메서드의 몇 번째 파라미터인지" 정보 (컨트롤러 메서드 흉내)
  private static MethodParameter headerParameter() throws NoSuchMethodException {
    Method method = GlobalExceptionHandlerRequestTest.class.getDeclaredMethod("sample", String.class);
    return new MethodParameter(method, 0);
  }

  @SuppressWarnings("unused")
  private static void sample(String idempotencyKey) {
  }
}
