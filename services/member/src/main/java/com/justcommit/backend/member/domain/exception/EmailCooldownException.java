package com.justcommit.backend.member.domain.exception;

import com.justcommit.backend.common.exception.BusinessException;
import lombok.Getter;

// 재발송 제한(429) 예외: 다시 요청할 수 있을 때까지 남은 시간을 함께 담음
// 컨트롤러가 Retry-After 헤더로 내려줌
@Getter
public class EmailCooldownException extends BusinessException  {

  private final long retryAfterSeconds;

  public EmailCooldownException(long retryAfterSeconds) {
    super(MemberErrorCode.EMAIL_SEND_COOLDOWN);
    this.retryAfterSeconds = retryAfterSeconds;
  }
}
