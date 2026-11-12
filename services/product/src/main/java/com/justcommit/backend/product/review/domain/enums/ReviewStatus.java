package com.justcommit.backend.product.review.domain.enums;

// 검수 기록의 진행 상태
public enum ReviewStatus {
    REQUESTED,  // 등록 · 수정 트랜잭션에서 만듦, 검수 대기
    DONE,       // 판정을 상품에 반영함
    STALE       // 검수 전 · 중에 상품이 다시 수정 · 삭제 · 예약돼 이 버전은 반영하지 않음
}
