package com.justcommit.backend.product.review.domain;

import com.justcommit.backend.product.review.domain.enums.ManualReason;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import com.justcommit.backend.product.review.domain.enums.ReviewDecision;

// 판정 결과. 반려면 rejectReason, 관리자 확인이면 manualReason이 있고, detail은 걸린 이름 · 단어 (관리자 화면용)
public record ReviewVerdict(ReviewDecision decision, RejectReason rejectReason, ManualReason manualReason, String detail) {

    public static ReviewVerdict approve() {
        return new ReviewVerdict(ReviewDecision.APPROVED, null, null, null);
    }

    public static ReviewVerdict reject(RejectReason reason, String detail) {
        return new ReviewVerdict(ReviewDecision.REJECTED, reason, null, detail);
    }

    public static ReviewVerdict manual(ManualReason reason, String detail) {
        return new ReviewVerdict(ReviewDecision.MANUAL, null, reason, detail);
    }
}
