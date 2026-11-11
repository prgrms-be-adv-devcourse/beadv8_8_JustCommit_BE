package com.justcommit.backend.product.banned.domain.enums;

public enum BannedKeywordType {
    BANNED,     // 글에 있으면 반려
    REVIEW,     // 글에 있으면 관리자 확인
    EXCEPTION   // 금지 이름이 들어 있어도 넘어가는 단어 (예: 꽃양귀비)
}
