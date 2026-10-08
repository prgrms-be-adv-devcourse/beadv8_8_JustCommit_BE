package com.justcommit.backend.member.presentation.dto;

import com.justcommit.backend.member.application.EmailSendResult;

// 인증번호 발송 응답 (프론트 표시용 남은 시간(단위: 초))
public record EmailSendResponse(long expiresIn, long resendAvailableIn) {

  public static EmailSendResponse from(EmailSendResult result) {
    return new EmailSendResponse(result.expiresIn(), result.resendAvailableIn());
  }
}
