package com.justcommit.backend.product.review.infrastructure;

import com.justcommit.backend.product.product.domain.ReviewScope;
import com.justcommit.backend.product.review.domain.ImageReview;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import com.justcommit.backend.product.review.domain.enums.ReviewDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ImageReviewRepository extends JpaRepository<ImageReview, Long> {

    Optional<ImageReview> findByProductIdAndReviewVersion(Long productId, int reviewVersion);

    // 하루 사진 검수 횟수 (사진 검수 요청 = scope FULL)
    long countBySellerIdAndScopeAndCreatedAtGreaterThanEqual(Long sellerId, ReviewScope scope, LocalDateTime from);

    // 최근 반려 수 (REVIEW_EXPIRED는 판매자 잘못이 아니라 제외)
    long countBySellerIdAndDecisionAndRejectReasonNotAndReviewedAtGreaterThanEqual(
            Long sellerId, ReviewDecision decision, RejectReason excluded, LocalDateTime from);

    // 재처리할 한 건을 잠금 (파드 여러 개가 동시에 돌아도 같은 건을 집지 않게 SKIP LOCKED)
    // 다음 시각이 지난 엔진 오류, 또는 만든 지 오래됐는데 아직 REQUESTED인 검수 (검수 요청이 DLT로 갔거나 사라짐)
    @Query(value = """
            SELECT id FROM image_review
            WHERE (status = 'ENGINE_ERROR' AND next_retry_at <= :now)
               OR (status = 'REQUESTED' AND created_at <= :staleBefore)
            ORDER BY COALESCE(next_retry_at, created_at), id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Long> lockNextRetryTarget(@Param("now") LocalDateTime now, @Param("staleBefore") LocalDateTime staleBefore);
}
