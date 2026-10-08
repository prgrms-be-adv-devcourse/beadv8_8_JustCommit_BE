package com.justcommit.backend.member.domain.exception;

import com.justcommit.backend.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

  // 이메일 인증
  EMAIL_SEND_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "인증번호는 잠시 후에 다시 요청할 수 있습니다."),
  EMAIL_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "인증 메일 발송에 실패했습니다. 잠시 후 다시 시도해주세요."),
  EMAIL_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증번호가 만료되었거나 요청되지 않았습니다. 인증번호를 다시 요청해주세요."),
  EMAIL_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "인증번호가 일치하지 않습니다.");

  private final HttpStatus status;
  private final String message;

}
