package com.justcommit.backend.product.review.domain.enums;

// 검수의 최종 판정(통과 · 반려)을 누가 했는지. 이의 신청은 엔진이 반려한 것(ENGINE)에만 할 수 있다
public enum DecidedBy {
    ENGINE,     // 자동 검수
    ADMIN,      // 관리자 확인
    DEADLINE    // 관리자 확인으로 간 지 24시간이 지나 REVIEW_EXPIRED로 자동 반려
}
