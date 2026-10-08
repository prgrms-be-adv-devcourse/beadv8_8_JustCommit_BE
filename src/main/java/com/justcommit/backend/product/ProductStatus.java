package com.justcommit.backend.product;

public enum ProductStatus {
    ACTIVE,           // 판매 중
    PAYMENT_PENDING,  // 주문서 작성 후 결제 대기 (예약됨)
    SOLD_OUT,          // 판매 완료
    DELETED           // 판매자가 삭제
}