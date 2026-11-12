package com.justcommit.backend.payment.infrastructure.pg;

// 토스에서 승인됐는지 알 수 없는 경우 (타임아웃 · 5xx · 네트워크 오류 등)
public class TossConfirmResultUnknownException extends RuntimeException {

    public TossConfirmResultUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
