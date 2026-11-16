package com.justcommit.backend.payment.infrastructure.pg;

// 조회 자체가 실패해 결제 상태를 알 수 없음 (타임아웃, 5xx, 429, 그 외 4xx, 응답 형식 오류)
public class TossLookupFailedException extends RuntimeException {

    public TossLookupFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
