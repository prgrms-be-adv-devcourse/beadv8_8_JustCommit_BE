package com.justcommit.backend.product.reservation.domain;

public enum ReservationStatus {
    RESERVED,  // 이 주문이 상품을 잡음
    RELEASED   // 결제 전에 풀림 (만료 · 결제 확정 실패). 이 orderId로 다시 예약하면 409
}
