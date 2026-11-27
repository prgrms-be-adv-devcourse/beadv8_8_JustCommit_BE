package com.justcommit.backend.market.shipment.infrastructure.courier;

import lombok.Getter;

@Getter
public class CourierBookingException extends RuntimeException {
    // 실패 종류 (#671). 예약 큐가 다시 시도할지 · 확인 필요로 멈출지 정한다
    private final CourierBookingFailure failure;

    public CourierBookingException(String message, Throwable cause, CourierBookingFailure failure) {
        super(message, cause);
        this.failure = failure;
    }

    public boolean isRetryable() {
        return failure.isRetryable();
    }
}
