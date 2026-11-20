package com.justcommit.backend.market.order.domain;

// 반품 신청을 승인, 거절한 쪽 (#383)
public enum ReturnDecidedBy {
    // 판매자가 직접 승인, 거절
    SELLER,
    // 응답 없이 기한이 지나 자동 승인
    AUTO
}
