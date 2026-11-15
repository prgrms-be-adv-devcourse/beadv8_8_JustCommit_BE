package com.justcommit.backend.product.review.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 반려 사유 코드 9개 (docs/flows/image-review.md 「관리자 수동 검수」)
@Getter
@RequiredArgsConstructor
public enum RejectReason {
    NOT_PLANT("식물 사진이 아니에요", true),
    HARMFUL_IMAGE("부적절한 사진이 포함돼 있어요", true),
    BANNED_SPECIES("판매 금지 품종이에요", true),
    PROHIBITED_FORM("씨앗 · 삽수 · 구근은 팔 수 없어요", true),
    REVIEW_EXPIRED("확인이 늦어져 반려됐어요. 다시 등록할 수 있어요", true),
    SPECIES_MISMATCH("선택한 품종과 사진 속 식물이 달라요", false),
    UNCLEAR_IMAGE("사진으로 상품을 확인하기 어려워요", false),
    INAPPROPRIATE_TEXT("제목이나 설명에 부적절한 내용이 있어요", true),
    OTHER("관리자가 입력한 내용", false);

    private final String message;       // 판매자에게 보여 줄 문구
    private final boolean autoRejectable;   // 엔진(또는 기한)이 자동으로 반려할 수 있는 사유
}
