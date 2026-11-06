package com.justcommit.backend.market.shipment.infrastructure.courier;

import lombok.Getter;

@Getter
public class CourierBookingException extends RuntimeException {
    private final boolean retryable;

    public CourierBookingException(String message, Throwable cause, boolean retryable) {
        // ① 부모(RuntimeException)에 메시지와 원래 예외를 넘기기
        // ② retryable 저장
        super(message, cause);
        this.retryable = retryable;
    }
}
