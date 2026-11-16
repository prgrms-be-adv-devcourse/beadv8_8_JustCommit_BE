package com.justcommit.backend.payment.infrastructure.pg;

// 토스에 해당 orderId의 결제가 없음 (404). 그 자체로 실패 근거가 아니다 (승인 전이거나 아직 반영 전일 수 있음)
public class TossPaymentNotFoundException extends RuntimeException {

    public TossPaymentNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
