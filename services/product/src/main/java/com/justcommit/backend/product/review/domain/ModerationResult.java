package com.justcommit.backend.product.review.domain;

// OpenAI Moderation 결과. 사진은 한 장이라도 flagged면 true
public record ModerationResult(boolean imageFlagged, boolean textFlagged) {

    // 글만 검사한 경우 (사진 검사 없음)
    public static ModerationResult textOnly(boolean textFlagged) {
        return new ModerationResult(false, textFlagged);
    }
}
