package com.justcommit.backend.market.shipment.domain;

// 선언 순서 = 배송 진행 순서. Shipment.applyCourierStatus가 이 순서로 "앞으로만"을 판단한다.
// CANCELLED는 맨 끝에 두어 취소 뒤에는 어떤 상태로도 바뀌지 않게 한다.
public enum ShipmentStatus {
    READY,
    BOOKED,
    PICKED_UP,
    IN_TRANSIT,
    DELIVERED,
    CANCELLED
}
