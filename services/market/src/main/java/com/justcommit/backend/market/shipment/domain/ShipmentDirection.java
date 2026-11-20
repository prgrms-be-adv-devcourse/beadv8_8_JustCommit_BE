package com.justcommit.backend.market.shipment.domain;

// 배송 방향 (#382). 가는 길은 판매자 주문당 1개(부분 유니크 인덱스), 반품은 주문상품마다 새 배송이라 여러 개
public enum ShipmentDirection {
    // 가는 길: 판매자 → 구매자. 구매 확정, 구매자 취소, 반품 기간은 이 배송으로 판정한다
    OUTBOUND,
    // 반품: 구매자 → 판매자 (#384에서 만든다)
    RETURN,
}
