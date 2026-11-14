package com.justcommit.backend.product.review.domain.enums;

// 관리자 확인으로 보낸 이유 (관리자 화면의 "금지 의심 사유")
public enum ManualReason {
    BANNED_SUSPECTED,       // 1순위 금지인데 0.7 미만 · 2 · 3순위 금지(확신도와 관계없이) · 변종 금지에 종까지만 일치
    REVIEW_KEYWORD,         // 제목 · 설명에 관리자 확인 단어 (예: 자생지)
    OTHER_SPECIES,          // 판매자가 "기타" 품종 선택
    LOW_CONFIDENCE,         // 후보 없음 또는 1순위 0.3 미만
    SPECIES_MISMATCH,       // 1순위가 판매자 품종과 속이 다름
    ENGINE_ERROR,           // 외부 API 오류(재시도 후에도) · 응답 해석 불가
    REPEATED_REJECTIONS     // 최근 7일 반려 5건 이상 → 자동 검수 없이
}
