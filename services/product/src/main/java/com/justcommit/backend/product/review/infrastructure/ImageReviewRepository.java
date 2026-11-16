package com.justcommit.backend.product.review.infrastructure;

import com.justcommit.backend.product.product.domain.ReviewScope;
import com.justcommit.backend.product.review.domain.ImageReview;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import com.justcommit.backend.product.review.domain.enums.ReviewDecision;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ImageReviewRepository extends JpaRepository<ImageReview, Long> {

    Optional<ImageReview> findByProductIdAndReviewVersion(Long productId, int reviewVersion);

    // 하루 사진 검수 횟수 (사진 검수 요청 = scope FULL)
    long countBySellerIdAndScopeAndCreatedAtGreaterThanEqual(Long sellerId, ReviewScope scope, LocalDateTime from);

    // 하루 사진 검수 횟수 (한도 확인용). 기한 초과(REVIEW_EXPIRED) 바로 다음 검수는 판매자 잘못이 아니라 세지 않음
    @Query(value = """
            SELECT COUNT(*) FROM image_review r
            WHERE r.seller_id = :sellerId AND r.scope = 'FULL' AND r.created_at >= :from
              AND NOT EXISTS (SELECT 1 FROM image_review prev
                              WHERE prev.product_id = r.product_id
                                AND prev.review_version = r.review_version - 1
                                AND prev.reject_reason = 'REVIEW_EXPIRED')
            """, nativeQuery = true)
    long countDailyPhotoReviews(@Param("sellerId") Long sellerId, @Param("from") LocalDateTime from);

    // 판매자 검수 기록 (최신 검수 버전부터)
    List<ImageReview> findByProductIdOrderByReviewVersionDesc(Long productId);

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

    // 관리자 확인 목록: 관리자 확인을 기다리는 검수 중 상품이 아직 그 버전 · 검수 중일 때만 (그사이 수정 · 삭제됐으면 빠짐), 오래된 순
    @Query(value = """
            SELECT r FROM ImageReview r JOIN Product p ON p.id = r.productId
            WHERE r.status = com.justcommit.backend.product.review.domain.enums.ReviewStatus.DONE
              AND r.decision = com.justcommit.backend.product.review.domain.enums.ReviewDecision.MANUAL
              AND p.reviewVersion = r.reviewVersion
              AND p.status = com.justcommit.backend.product.ProductStatus.REVIEW_PENDING
            ORDER BY r.reviewedAt, r.id
            """,
            countQuery = """
            SELECT COUNT(r) FROM ImageReview r JOIN Product p ON p.id = r.productId
            WHERE r.status = com.justcommit.backend.product.review.domain.enums.ReviewStatus.DONE
              AND r.decision = com.justcommit.backend.product.review.domain.enums.ReviewDecision.MANUAL
              AND p.reviewVersion = r.reviewVersion
              AND p.status = com.justcommit.backend.product.ProductStatus.REVIEW_PENDING
            """)
    Page<ImageReview> findAwaitingAdmin(Pageable pageable);

    // 관리자 처리: 두 관리자가 동시에 눌러도 한 명씩 (늦은 쪽은 이미 처리된 것을 보고 409)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ImageReview r WHERE r.id = :id")
    Optional<ImageReview> findWithLockById(@Param("id") Long id);

    // 24시간 기한: 관리자 확인으로 간 지 기한이 지난 한 건을 잠금 (파드 여러 개여도 같은 건을 집지 않게 SKIP LOCKED)
    @Query(value = """
            SELECT id FROM image_review
            WHERE status = 'DONE' AND decision = 'MANUAL' AND reviewed_at <= :expireBefore
            ORDER BY reviewed_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Long> lockNextExpiredTarget(@Param("expireBefore") LocalDateTime expireBefore);
}
