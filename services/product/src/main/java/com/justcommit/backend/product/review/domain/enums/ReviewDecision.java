package com.justcommit.backend.product.review.domain.enums;

// 검수 판정
public enum ReviewDecision {
    APPROVED,   // 통과 → ACTIVE
    REJECTED,   // 반려 → REJECTED
    MANUAL      // 관리자 확인
}
