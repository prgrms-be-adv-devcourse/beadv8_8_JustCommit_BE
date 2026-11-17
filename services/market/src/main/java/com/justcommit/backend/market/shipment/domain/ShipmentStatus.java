package com.justcommit.backend.market.shipment.domain;

// 선언 순서 = 배송 진행 순서. Shipment.applyCourierStatus가 이 순서로 "앞으로만"을 판단한다.
// CANCEL_PENDING · CANCELLED는 맨 끝에 두어 취소가 시작된 뒤에는 택배사 진행 웹훅으로 상태가 바뀌지 않게 한다
// (취소 결과는 택배 취소 큐가 정한다, ADR 0025).
public enum ShipmentStatus {
    READY,
    BOOKED,
    // 기사가 수거지 2km 안: 구매자가 취소할 수 없음
    CANCEL_LOCKED,
    PICKED_UP,
    IN_TRANSIT,
    DELIVERED,
    // 취소를 기록했고 택배 취소 큐가 택배사 결과를 기다림
    CANCEL_PENDING,
    CANCELLED
}
