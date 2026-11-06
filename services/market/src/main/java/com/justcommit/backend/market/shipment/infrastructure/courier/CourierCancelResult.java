package com.justcommit.backend.market.shipment.infrastructure.courier;

// 택배사 예약 취소 결과. 다시 보내도 결과가 같은 응답들이다.
public enum CourierCancelResult {
    CANCELLED,        // 200: 취소됨 또는 이미 취소됨
    NOT_FOUND,        // 404: 택배사에 없는 송장번호
    NOT_CANCELLABLE   // 409: 이미 수거됨
}
