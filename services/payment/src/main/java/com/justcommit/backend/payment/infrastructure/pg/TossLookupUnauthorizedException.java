package com.justcommit.backend.payment.infrastructure.pg;

// 시크릿 키나 권한 설정 문제 (401, 403). 충전 건의 문제가 아니다
public class TossLookupUnauthorizedException extends RuntimeException {

    public TossLookupUnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
