package com.justcommit.backend.market;

// 반품 사유 (#382). 판매자 귀책만 받는다 (단순 변심 반품 없음). 기타면 직접 작성 내용이 필수
public enum ReturnReason {
    // 상품 파손
    DAMAGED,
    // 설명과 다름
    NOT_AS_DESCRIBED,
    // 기타
    OTHER
}
