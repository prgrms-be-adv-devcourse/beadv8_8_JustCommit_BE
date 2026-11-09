package com.justcommit.backend.gateway.error;

import com.justcommit.backend.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Gateway가 직접 내는 에러. 401 · 403은 서비스 쪽(common-security-servlet SecurityErrorCode)과 이름 · 문구를 맞춤
 * → 프론트는 Gateway가 막았든 서비스가 막았든 같은 응답을 받는다.
 */
public enum GatewayErrorCode implements ErrorCode {

  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
  TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
  INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
  FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
  SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.");

  private final HttpStatus status;
  private final String message;

  GatewayErrorCode(HttpStatus status, String message) {
    this.status = status;
    this.message = message;
  }

  @Override
  public HttpStatus getStatus() {
    return status;
  }

  @Override
  public String getMessage() {
    return message;
  }
}
