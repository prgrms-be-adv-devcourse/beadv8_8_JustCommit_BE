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
  // 서비스 장애 (노션 정책 정리 10.8). 504 · 502는 서비스가 처리했을 수도 있어 "처리 내역 확인" 안내
  SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
  GATEWAY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "처리 결과를 확인하지 못했습니다. 잠시 후 처리 내역을 확인해 주세요."),
  BAD_GATEWAY(HttpStatus.BAD_GATEWAY, "응답을 받는 중 연결이 끊겼습니다. 잠시 후 처리 내역을 확인해 주세요."),
  // 이름 · 문구는 common-web CommonErrorCode와 같음
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

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
