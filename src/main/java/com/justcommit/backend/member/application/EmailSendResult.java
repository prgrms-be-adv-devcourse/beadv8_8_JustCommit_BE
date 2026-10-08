package com.justcommit.backend.member.application;

// 인증번호 발송 결과(프론트 표시용 남은 시간, 단위: 초)
public record EmailSendResult(long expiresIn, long resendAvailableIn) {
}
