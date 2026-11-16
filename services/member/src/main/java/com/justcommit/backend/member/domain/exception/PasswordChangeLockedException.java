package com.justcommit.backend.member.domain.exception;

import com.justcommit.backend.common.exception.BusinessException;
import lombok.Getter;

// 현재 비밀번호를 여러 번 틀려 비밀번호 변경이 막힘 (429, #412). 다시 시도할 수 있을 때까지 남은 초를 함께 담아
// 컨트롤러가 Retry-After 헤더로 내려준다 (재발송 제한 예외와 같은 방식)
@Getter
public class PasswordChangeLockedException extends BusinessException {

  private final long retryAfterSeconds;

  public PasswordChangeLockedException(long retryAfterSeconds) {
    super(MemberErrorCode.PASSWORD_CHANGE_LOCKED);
    this.retryAfterSeconds = retryAfterSeconds;
  }
}
