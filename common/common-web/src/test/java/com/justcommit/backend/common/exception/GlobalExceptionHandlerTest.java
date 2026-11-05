package com.justcommit.backend.common.exception;

import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
import com.justcommit.backend.common.client.exception.InternalApiUnavailableException;
import com.justcommit.backend.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void 연결_실패는_503() {
    ResponseEntity<ApiResponse<Void>> response = handler.handleInternalApiUnavailable(
        new InternalApiUnavailableException("payment", new ConnectException()));

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_API_UNAVAILABLE");
  }

  @Test
  void 응답_시간_초과는_504() {
    ResponseEntity<ApiResponse<Void>> response = handler.handleInternalApiResultUnknown(
        new InternalApiResultUnknownException("payment", new HttpTimeoutException("timeout")));

    assertThat(response.getStatusCode().value()).isEqualTo(504);
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_API_TIMEOUT");
  }

  @Test
  void 상대가_503이면_503() {
    ResponseEntity<ApiResponse<Void>> response = handler.handleInternalApiError(
        new InternalApiErrorException("payment", 503, null, "payment 서비스가 503 응답을 보냈습니다."));

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_API_UNAVAILABLE");
  }

  @Test
  void 처리_안_된_그_밖의_에러_응답은_500이고_상대_코드를_노출하지_않는다() {
    ResponseEntity<ApiResponse<Void>> response = handler.handleInternalApiError(
        new InternalApiErrorException("payment", 409, "INSUFFICIENT_BALANCE", "잔액이 부족합니다."));

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    assertThat(response.getBody().message()).doesNotContain("잔액");
  }
}
