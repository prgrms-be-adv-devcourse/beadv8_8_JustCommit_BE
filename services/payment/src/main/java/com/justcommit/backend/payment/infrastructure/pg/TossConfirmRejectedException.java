package com.justcommit.backend.payment.infrastructure.pg;

import lombok.Getter;

// 토스가 승인을 명확히 거절한 경우 (돈이 빠져나가지 않음)
@Getter
public class TossConfirmRejectedException extends RuntimeException {

    private final String code;

    public TossConfirmRejectedException(String code, String message) {
        super("[" + code + "] " + message);
        this.code = code;
    }
}
