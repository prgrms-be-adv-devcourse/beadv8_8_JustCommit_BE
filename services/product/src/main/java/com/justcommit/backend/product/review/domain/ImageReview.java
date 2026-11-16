package com.justcommit.backend.product.review.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import com.justcommit.backend.product.product.domain.ReviewScope;
import com.justcommit.backend.product.review.domain.enums.DecidedBy;
import com.justcommit.backend.product.review.domain.enums.ManualReason;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import com.justcommit.backend.product.review.domain.enums.ReviewDecision;
import com.justcommit.backend.product.review.domain.enums.ReviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 사진 검수 기록. 검수 버전마다 한 행 (상품 ID + 검수 버전 UNIQUE → 같은 검수 요청이 두 번 와도 한 번만 처리)
@Entity
@Table(name = "image_review",
        uniqueConstraints = @UniqueConstraint(name = "uk_image_review_product_version", columnNames = {"product_id", "review_version"}),
        indexes = {
                @Index(name = "idx_image_review_seller_created", columnList = "seller_id, created_at"),
                @Index(name = "idx_image_review_seller_reviewed", columnList = "seller_id, reviewed_at"),
                @Index(name = "idx_image_review_status_next_retry", columnList = "status, next_retry_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageReview extends BaseTimeEntity {

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "review_version", nullable = false)
    private int reviewVersion;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewScope scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ReviewDecision decision;

    @Enumerated(EnumType.STRING)
    @Column(name = "reject_reason", length = 30)
    private RejectReason rejectReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "manual_reason", length = 30)
    private ManualReason manualReason;

    @Column(length = 200)
    private String detail;

    @Column(name = "engine_result", columnDefinition = "TEXT")
    private String engineResult;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    // 엔진 오류 재처리 횟수 (최대 3회, 처음 검수는 세지 않음)
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    // 이 시각이 지나면 스케줄러가 다시 검수 (ENGINE_ERROR일 때만 씀)
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    // 최종 판정(통과 · 반려)을 누가 했는지. 관리자 확인 대기 중이면 null
    @Enumerated(EnumType.STRING)
    @Column(name = "decided_by", length = 20)
    private DecidedBy decidedBy;

    @Column(name = "admin_id")
    private Long adminId;

    // 관리자가 OTHER로 반려할 때 입력한 내용 (판매자에게 그대로 보여 줌)
    @Column(name = "admin_memo", length = 200)
    private String adminMemo;

    // 최종 판정 시각. reviewedAt(엔진 판정 · 관리자 확인 기한의 시작)과 다름
    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    private ImageReview(Long productId, int reviewVersion, Long sellerId, ReviewScope scope) {
        this.productId = productId;
        this.reviewVersion = reviewVersion;
        this.sellerId = sellerId;
        this.scope = scope;
        this.status = ReviewStatus.REQUESTED;
    }

    public static ImageReview requested(Long productId, int reviewVersion, Long sellerId, ReviewScope scope) {
        return new ImageReview(productId, reviewVersion, sellerId, scope);
    }

    public boolean isRequested() {
        return status == ReviewStatus.REQUESTED;
    }

    // 엔진 오류: 상품은 그대로 두고 nextRetryAt 뒤에 다시 검수
    public void markEngineError(String engineResult, String errorMessage, LocalDateTime nextRetryAt) {
        this.status = ReviewStatus.ENGINE_ERROR;
        this.engineResult = engineResult;
        this.errorMessage = truncate(errorMessage, 500);
        this.nextRetryAt = nextRetryAt;
    }

    // 스케줄러가 재처리를 맡음: 횟수 +1, 처리하는 동안 다른 파드가 다시 집지 않게 다음 시각을 뒤로 미룸
    // (도중에 서버가 죽으면 그 시각 뒤에 다시 집힘, 그 시도도 1회로 셈)
    // 멈춘 REQUESTED도 ENGINE_ERROR로 바꿔 맡음 → 늦게 도착한 검수 요청 이벤트는 REQUESTED가 아니라서 건너뜀
    public void startRetry(LocalDateTime leaseUntil) {
        this.status = ReviewStatus.ENGINE_ERROR;
        this.retryCount++;
        this.nextRetryAt = leaseUntil;
    }

    // 판정을 상품에 반영한 뒤 기록
    public void complete(ReviewVerdict verdict, String engineResult, String errorMessage, LocalDateTime now) {
        this.status = ReviewStatus.DONE;
        this.nextRetryAt = null;
        this.decision = verdict.decision();
        this.rejectReason = verdict.rejectReason();
        this.manualReason = verdict.manualReason();
        this.detail = truncate(verdict.detail(), 200);
        this.engineResult = engineResult;
        this.errorMessage = truncate(errorMessage, 500);
        this.reviewedAt = now;
        if (verdict.decision() != ReviewDecision.MANUAL) {
            this.decidedBy = DecidedBy.ENGINE;
            this.decidedAt = now;
        }
    }

    // 관리자 확인을 기다리는 중인지 (관리자 처리 · 24시간 기한 대상)
    public boolean isAwaitingAdmin() {
        return status == ReviewStatus.DONE && decision == ReviewDecision.MANUAL;
    }

    // 관리자가 통과(reason null) 또는 반려. 엔진이 관리자 확인으로 보낸 사유(manualReason · detail)는 그대로 둠
    public void decideByAdmin(Long adminId, ReviewDecision decision, RejectReason reason, String memo, LocalDateTime now) {
        this.decision = decision;
        this.rejectReason = reason;
        this.adminMemo = reason == RejectReason.OTHER ? memo : null;
        this.adminId = adminId;
        this.decidedBy = DecidedBy.ADMIN;
        this.decidedAt = now;
    }

    // 관리자 확인으로 간 지 24시간이 지나 자동 반려
    public void expire(LocalDateTime now) {
        this.decision = ReviewDecision.REJECTED;
        this.rejectReason = RejectReason.REVIEW_EXPIRED;
        this.decidedBy = DecidedBy.DEADLINE;
        this.decidedAt = now;
    }

    // 그사이 상품이 바뀌어 이 버전은 반영하지 않음
    public void markStale() {
        this.status = ReviewStatus.STALE;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
