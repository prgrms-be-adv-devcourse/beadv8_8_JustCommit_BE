package com.justcommit.backend.market.order.domain;

// 반품 신청의 진행 상태 (#382). 주문상품 상태(RETURN_*)와 함께 바뀐다
public enum ReturnRequestStatus {
    // 판매자의 승인, 거절을 기다린다
    REQUESTED,
    // 판매자 승인 또는 자동 승인 (#383)
    APPROVED,
    // 판매자 거절. 주문상품은 바로 구매 확정 (#383)
    REJECTED,
    // 반품 환불 확정 (#384)
    COMPLETED
}
