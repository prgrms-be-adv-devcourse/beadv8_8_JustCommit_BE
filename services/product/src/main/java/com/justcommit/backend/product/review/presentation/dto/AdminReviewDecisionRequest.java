package com.justcommit.backend.product.review.presentation.dto;

import com.justcommit.backend.product.review.domain.enums.RejectReason;
import com.justcommit.backend.product.review.domain.enums.ReviewDecision;
import jakarta.validation.constraints.NotNull;

// 관리자 처리: decision은 APPROVED · REJECTED. 반려면 rejectReason 필수, OTHER면 memo 필수(200자 이하)
// 사유 · 내용 규칙은 서비스에서 확인 (400 INVALID_REJECT_REASON)
public record AdminReviewDecisionRequest(
        @NotNull ReviewDecision decision,
        RejectReason rejectReason,
        String memo
) {
}
