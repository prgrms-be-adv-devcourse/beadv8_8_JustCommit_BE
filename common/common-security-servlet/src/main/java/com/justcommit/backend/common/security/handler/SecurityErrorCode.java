package com.justcommit.backend.common.security.handler;

import com.justcommit.backend.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// Security 필터 단계(401/403) 전용 에러 코드(handler 패키지 안에서만 사용)
@Getter
@RequiredArgsConstructor
enum SecurityErrorCode implements ErrorCode {

  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
  TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
  INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
  FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.");

  private final HttpStatus status;
  private final String message;
}
