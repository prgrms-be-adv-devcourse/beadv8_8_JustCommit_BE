package com.justcommit.backend.product.review.presentation.dto;

import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.review.domain.ImageReview;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import com.justcommit.backend.product.review.domain.enums.ReviewDecision;
import com.justcommit.backend.product.review.domain.enums.ReviewStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

// 판매자 본인 상품의 검수 기록 (최신 검수 버전부터) · 남은 재검수 횟수 · 이의 신청 · 기한 초과 재요청 가능 여부
public record SellerImageReviewHistoryResponse(
        Long productId,
        String productStatus,
        int remainingResubmits,         // 반려된 상품을 고쳐 다시 검수받을 수 있는 남은 횟수
        boolean appealable,             // 지금 이의 신청 가능 (자동 반려 · 같은 검수 버전에서 아직 안 함)
        boolean resubmittable,          // 지금 기한 초과 재요청 가능 (REVIEW_EXPIRED)
        List<Item> reviews
) {

    // 검수 버전 하나
    public record Item(
            int reviewVersion,
            String scope,               // FULL(사진 검수) · TEXT_ONLY(판매 중 상품의 글 검사)
            DisplayStatus status,
            String rejectReason,        // 반려 사유 코드 (반려가 아니면 null)
            String rejectMessage,       // 판매자에게 보여 줄 문구 (OTHER면 관리자가 입력한 내용)
            String decidedBy,           // ENGINE(자동) · ADMIN(관리자) · DEADLINE(기한 초과), 아직 판정 전이면 null
            String appealMemo,
            LocalDateTime requestedAt,
            LocalDateTime decidedAt,
            LocalDateTime expiresAt     // 관리자 확인 기한 (관리자 확인 중 · 지연일 때만, 관리자 목록과 같은 값). 지나면 REVIEW_EXPIRED로 자동 반려
    ) {
    }

    // 판매자 화면에 보여 줄 상태
    public enum DisplayStatus {
        IN_REVIEW,              // 검수 중 (엔진 오류 재처리 포함, "확인에 시간이 조금 더 걸리고 있어요")
        ADMIN_CHECK,            // 관리자 확인 중 (이의 신청 포함)
        ADMIN_CHECK_DELAYED,    // 관리자 확인 12시간 지남 ("확인에 시간이 걸리고 있어요")
        APPROVED,
        REJECTED,
        REPLACED                // 그사이 수정 · 삭제돼 이 버전은 반영하지 않음
    }

    public static SellerImageReviewHistoryResponse of(Product product, List<ImageReview> reviews,
                                                      boolean appealable, boolean resubmittable,
                                                      LocalDateTime now, Duration delayAfter, Duration expireAfter) {
        return new SellerImageReviewHistoryResponse(
                product.getId(), product.getStatus().name(), product.remainingResubmits(), appealable, resubmittable,
                reviews.stream().map(review -> item(review, now, delayAfter, expireAfter)).toList());
    }

    // 검수 기록 한 줄. 내 상품 목록(GET products/me)의 검수 배지도 같은 규칙으로 만듦 (SellerImageReviewService.latestReviews)
    public static Item item(ImageReview review, LocalDateTime now, Duration delayAfter, Duration expireAfter) {
        DisplayStatus status = displayStatus(review, now, delayAfter);
        boolean awaitingAdmin = status == DisplayStatus.ADMIN_CHECK || status == DisplayStatus.ADMIN_CHECK_DELAYED;
        RejectReason reason = review.getDecision() == ReviewDecision.REJECTED ? review.getRejectReason() : null;
        return new Item(
                review.getReviewVersion(),
                review.getScope().name(),
                status,
                reason == null ? null : reason.name(),
                reason == null ? null : (reason == RejectReason.OTHER ? review.getAdminMemo() : reason.getMessage()),
                review.getDecidedBy() == null ? null : review.getDecidedBy().name(),
                review.getAppealMemo(),
                review.getCreatedAt(),
                review.getDecidedAt(),
                awaitingAdmin ? review.adminCheckExpiresAt(expireAfter) : null);
    }

    private static DisplayStatus displayStatus(ImageReview review, LocalDateTime now, Duration delayAfter) {
        if (review.getStatus() == ReviewStatus.STALE) {
            return DisplayStatus.REPLACED;
        }
        if (review.getStatus() != ReviewStatus.DONE) {
            return DisplayStatus.IN_REVIEW;
        }
        return switch (review.getDecision()) {
            case APPROVED -> DisplayStatus.APPROVED;
            case REJECTED -> DisplayStatus.REJECTED;
            case MANUAL -> review.getReviewedAt().plus(delayAfter).isAfter(now)
                    ? DisplayStatus.ADMIN_CHECK : DisplayStatus.ADMIN_CHECK_DELAYED;
        };
    }
}
