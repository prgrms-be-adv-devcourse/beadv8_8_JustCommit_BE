package com.justcommit.backend.market.shipment.domain;

// 선언 순서 = 배송 진행 순서. Shipment.applyCourierStatus가 이 순서로 "앞으로만"을 판단한다.
// CANCEL_PENDING · CANCELLED는 맨 끝에 두어 취소가 시작된 뒤에는 택배사 진행 웹훅으로 상태가 바뀌지 않게 한다
// (취소 결과는 택배 취소 큐가 정한다, ADR 0026).
//
// REVIEW_REQUIRED(확인 필요)는 READY 바로 뒤: 택배사가 다시 보내도 같은 결과로 거절해 예약 큐가 멈춘 배송 (#671).
// 사람이 고쳐 READY로 되돌리기 전까지 큐가 집지 않는다. 실제로는 택배사에 예약이 있었다면(멱등 키 409) 뒤이은 웹훅으로 앞으로 간다.
// 화면 · 주문 모듈에는 READY로 보인다(publicName) — 구매자 · 판매자에게는 아직 픽업 전이고, 구매자 취소도 READY처럼 받는다.
public enum ShipmentStatus {
    READY,
    REVIEW_REQUIRED,
    BOOKED,
    // 기사가 수거지 2km 안: 구매자가 취소할 수 없음
    CANCEL_LOCKED,
    PICKED_UP,
    IN_TRANSIT,
    DELIVERED,
    // 취소를 기록했고 택배 취소 큐가 택배사 결과를 기다림
    CANCEL_PENDING,
    CANCELLED;

    // 다른 모듈 · 화면에 보여 줄 이름. 확인 필요는 운영 내부 상태라 READY로 보인다
    public String publicName() {
        return this == REVIEW_REQUIRED ? READY.name() : name();
    }
}
