package com.justcommit.backend.market;

public enum OrderItemStatus {
    // 수락 대기: 결제됐고 판매자의 승인 · 거절을 기다린다. 배송은 아직 없다 (#284, ADR 0013)
    ACCEPT_PENDING,
    // 일반: 결제 전이거나 판매자가 수락한 주문상품
    NORMAL,
    // 취소 중: 구매자가 취소했고 택배 취소 결과 또는 환불 결과를 기다린다. 환불이 확정되면 CANCELLED (#282, ADR 0026)
    CANCEL_PROCESSING,
    CANCELLED,
    // 확인 필요: 환불이 거절(4xx)됐거나 복구 5번으로도 환불 결과를 모름. 사람이 확인한다 (#282)
    REVIEW_REQUIRED,
    REFUND_REQUESTED,
    REFUNDED,
    CONFIRMED,
}
