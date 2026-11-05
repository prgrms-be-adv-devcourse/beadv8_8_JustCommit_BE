package com.justcommit.backend.common.exception;

import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
import com.justcommit.backend.common.client.exception.InternalApiUnavailableException;
import com.justcommit.backend.common.response.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  @DisplayName("연결 실패는 503")
  void returns503OnUnavailable() {
    ResponseEntity<ApiResponse<Void>> response = handler.handleInternalApiUnavailable(
        new InternalApiUnavailableException("payment", new ConnectException()));

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_API_UNAVAILABLE");
  }

  @Test
  @DisplayName("응답 시간 초과는 504")
  void returns504OnResultUnknown() {
    ResponseEntity<ApiResponse<Void>> response = handler.handleInternalApiResultUnknown(
        new InternalApiResultUnknownException("payment", new HttpTimeoutException("timeout")));

    assertThat(response.getStatusCode().value()).isEqualTo(504);
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_API_TIMEOUT");
  }

  @Test
  @DisplayName("상대가 503이면 503")
  void returns503WhenUpstream503() {
    ResponseEntity<ApiResponse<Void>> response = handler.handleInternalApiError(
        new InternalApiErrorException("payment", 503, null, "payment 서비스가 503 응답을 보냈습니다."));

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_API_UNAVAILABLE");
  }

  @Test
  @DisplayName("처리 안 된 그 밖의 에러 응답은 500이고 상대 코드를 노출하지 않는다")
  void returns500WithoutUpstreamCode() {
    ResponseEntity<ApiResponse<Void>> response = handler.handleInternalApiError(
        new InternalApiErrorException("payment", 409, "INSUFFICIENT_BALANCE", "잔액이 부족합니다."));

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    assertThat(response.getBody().message()).doesNotContain("잔액");
  }
}
