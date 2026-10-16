package com.justcommit.backend.market.shipment.infrastructure.courier;

// 택배사 예약 취소가 결과 없이 실패한 경우 (타임아웃, 5xx 등). 취소는 멱등이라 그대로 다시 보내면 된다.
public class CourierCancelException extends RuntimeException {

    public CourierCancelException(String message, Throwable cause) {
        super(message, cause);
    }
}
