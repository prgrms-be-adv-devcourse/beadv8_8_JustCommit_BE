package com.justcommit.backend.product.review.infrastructure;

import com.justcommit.backend.product.product.domain.ReviewScope;
import com.justcommit.backend.product.review.domain.ImageReview;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import com.justcommit.backend.product.review.domain.enums.ReviewDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ImageReviewRepository extends JpaRepository<ImageReview, Long> {

    Optional<ImageReview> findByProductIdAndReviewVersion(Long productId, int reviewVersion);

    // 하루 사진 검수 횟수 (사진 검수 요청 = scope FULL)
    long countBySellerIdAndScopeAndCreatedAtGreaterThanEqual(Long sellerId, ReviewScope scope, LocalDateTime from);

    // 최근 반려 수 (REVIEW_EXPIRED는 판매자 잘못이 아니라 제외)
    long countBySellerIdAndDecisionAndRejectReasonNotAndReviewedAtGreaterThanEqual(
            Long sellerId, ReviewDecision decision, RejectReason excluded, LocalDateTime from);
}
