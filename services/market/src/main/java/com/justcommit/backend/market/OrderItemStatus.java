package com.justcommit.backend.market;

public enum OrderItemStatus {
    // 수락 대기: 결제됐고 판매자의 수락, 거절을 기다린다. 배송은 아직 없다 (#284, ADR 0013)
    ACCEPT_PENDING,
    // 일반: 결제 전이거나 판매자가 수락한 주문상품
    NORMAL,
    // 취소 중: 구매자가 취소했고 택배 취소 결과 또는 환불 결과를 기다린다. 환불이 확정되면 CANCELLED (#282, ADR 0026)
    CANCEL_PROCESSING,
    CANCELLED,
    // 확인 필요: 환불이 거절(4xx)됐거나 복구 5번으로도 환불 결과를 모름. 사람이 확인한다 (#282)
    REVIEW_REQUIRED,
    // 반품 신청: 구매자가 반품을 신청했고 판매자의 승인, 거절을 기다린다. 자동 구매 확정에서 빠진다 (#382)
    RETURN_REQUESTED,
    // 반품 배송 중: 반품이 승인돼 반품 택배가 판매자에게 가는 중 (#383)
    RETURN_IN_TRANSIT,
    // 반품 환불 중: 반품 택배가 판매자에게 도착했고 환불 결과를 기다린다. 환불이 확정되면 RETURNED (#384)
    RETURN_PROCESSING,
    // 반품 완료: 반품 환불이 확정됐다. 택배사 RETURNED(반송)와 다르다 (#384)
    RETURNED,
    CONFIRMED,
}
