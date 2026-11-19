package com.justcommit.backend.product.review.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.client.member.MemberQuery;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.member.domain.MemberReplica;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.event.ImageReviewDelayed;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.review.domain.ImageReview;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import com.justcommit.backend.product.review.domain.enums.ReviewDecision;
import com.justcommit.backend.product.review.domain.enums.ReviewStatus;
import com.justcommit.backend.product.review.infrastructure.ImageReviewRepository;
import com.justcommit.backend.product.review.presentation.dto.AdminImageReviewResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// 관리자 검수 확인 (docs/flows/image-review.md 「관리자 수동 검수」)
// 엔진이 관리자 확인(decision = MANUAL)으로 보낸 검수를 관리자가 통과 · 반려하고, 24시간 안에 못 보면 REVIEW_EXPIRED로 자동 반려
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminImageReviewService {

    // 관리자 확인으로 간 지 이만큼 지나면 목록에 「지연」 표시 + 관리자 알림 이벤트(ImageReviewDelayed, 09~21시 매 정각 묶어서) 1번
    static final Duration DELAY_AFTER = Duration.ofHours(12);
    // 이만큼 지나면 자동 반려 (자동 승인은 하지 않음)
    static final Duration EXPIRE_AFTER = Duration.ofHours(24);
    static final int MAX_EXPIRES_PER_RUN = 50;   // 스케줄러 한 번에 처리할 최대 건수
    static final int MAX_MEMO_LENGTH = 200;

    private final ImageReviewRepository imageReviewRepository;
    private final ProductRepository productRepository;
    private final MemberQuery memberQuery;
    private final ReviewResultPublisher reviewResultPublisher;
    private final OutboxPublisher outboxPublisher;
    private final TransactionTemplate transactionTemplate;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    // ── 목록 ───────────────────────────────

    // 관리자 확인 대기 목록, 오래된 순. 그사이 수정 · 삭제된 상품은 빠짐
    @Transactional(readOnly = true)
    public Page<AdminImageReviewResponse> findAwaiting(Pageable pageable) {
        Page<ImageReview> page = imageReviewRepository.findAwaitingAdmin(pageable);
        List<Long> productIds = page.getContent().stream().map(ImageReview::getProductId).toList();
        // 품종은 함께 조회, 사진은 Product의 @BatchSize로 한 번에
        Map<Long, Product> products = productRepository.findWithSpeciesByIdIn(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        // 판매자 닉네임은 회원 복제본에서 한 번에. 복제본이 없으면 「알 수 없는 회원」
        Map<Long, String> nicknames = memberQuery.getNicknames(
                page.getContent().stream().map(ImageReview::getSellerId).distinct().toList());
        LocalDateTime now = LocalDateTime.now(clock);
        return page.map(review -> {
            LocalDateTime reviewedAt = review.getReviewedAt();
            return AdminImageReviewResponse.of(review, products.get(review.getProductId()),
                    nicknames.getOrDefault(review.getSellerId(), MemberReplica.UNKNOWN_NICKNAME), engineResult(review),
                    review.adminCheckExpiresAt(EXPIRE_AFTER), !reviewedAt.plus(DELAY_AFTER).isAfter(now));
        });
    }

    private JsonNode engineResult(ImageReview review) {
        if (review.getEngineResult() == null) {
            return null;
        }
        try {
            return jsonMapper.readTree(review.getEngineResult());
        } catch (RuntimeException e) {
            log.warn("엔진 결과 JSON을 읽지 못함: reviewId={}", review.getId(), e);
            return null;
        }
    }

    // ── 처리 ───────────────────────────────

    // 통과 → ACTIVE, 반려 → REJECTED. 판매자 알림 이벤트는 자동 검수와 같음
    // 두 관리자가 동시에 누르면 검수 기록 행 잠금으로 한 명씩, 늦은 쪽은 409 REVIEW_ALREADY_PROCESSED
    // 그사이 판매자가 수정 · 삭제했으면(버전 · 상태가 다름) 409 REVIEW_OUTDATED
    @Transactional
    public void decide(Long reviewId, Long adminId, ReviewDecision decision, RejectReason reason, String memo) {
        validate(decision, reason, memo);
        ImageReview review = imageReviewRepository.findWithLockById(reviewId)
                .orElseThrow(() -> new BusinessException(ProductErrorCode.REVIEW_NOT_FOUND));
        if (!review.isAwaitingAdmin()) {
            throw new BusinessException(review.getStatus() == ReviewStatus.STALE
                    ? ProductErrorCode.REVIEW_OUTDATED : ProductErrorCode.REVIEW_ALREADY_PROCESSED);
        }
        ProductStatus to = decision == ReviewDecision.APPROVED ? ProductStatus.ACTIVE : ProductStatus.REJECTED;
        if (productRepository.applyReviewResult(review.getProductId(), review.getReviewVersion(),
                ProductStatus.REVIEW_PENDING, to) == 0) {
            throw new BusinessException(ProductErrorCode.REVIEW_OUTDATED);
        }
        Product product = productRepository.findById(review.getProductId()).orElseThrow();
        RejectReason rejectReason = decision == ReviewDecision.APPROVED ? null : reason;
        review.decideByAdmin(adminId, decision, rejectReason, memo, LocalDateTime.now(clock));
        if (decision == ReviewDecision.APPROVED) {
            reviewResultPublisher.approved(product.getId(), review.getSellerId(), review.getReviewVersion(), product.getTitle());
        } else {
            reviewResultPublisher.rejected(product.getId(), review.getSellerId(), review.getReviewVersion(), product.getTitle(),
                    rejectReason, review.getAdminMemo());
        }
        log.info("관리자 검수 처리: reviewId={}, productId={}, reviewVersion={}, adminId={}, decision={}, rejectReason={}",
                reviewId, product.getId(), review.getReviewVersion(), adminId, decision, rejectReason);
    }

    // 통과 · 반려만. 반려는 사유 필수, REVIEW_EXPIRED는 기한 자동 반려 전용, OTHER는 내용 필수 200자
    private static void validate(ReviewDecision decision, RejectReason reason, String memo) {
        if (decision == ReviewDecision.APPROVED) {
            return;
        }
        boolean valid = decision == ReviewDecision.REJECTED
                && reason != null
                && reason != RejectReason.REVIEW_EXPIRED
                && (reason != RejectReason.OTHER || (StringUtils.hasText(memo) && memo.length() <= MAX_MEMO_LENGTH));
        if (!valid) {
            throw new BusinessException(ProductErrorCode.INVALID_REJECT_REASON);
        }
    }

    // ── 12시간 지연 알림 ───────────────────────────────

    // 12시간은 지났고 24시간은 아직 안 지났으며 아직 알리지 않은 관리자 확인 건을 묶어 ImageReviewDelayed 1개를 Outbox에 저장하고, 그 건들에 알림 시각을 채움
    // 저장과 표시가 한 트랜잭션이라 둘 중 하나만 남지 않음 (롤백되면 둘 다 없음 → 다음 실행에서 다시 모음). 0건이면 보내지 않음
    // 이번에 묶은 건수를 돌려줌
    @Transactional
    public int notifyDelayed() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<ImageReview> delayed = imageReviewRepository.lockDelayedUnnotified(now.minus(DELAY_AFTER), now.minus(EXPIRE_AFTER));
        if (delayed.isEmpty()) {
            return 0;
        }
        delayed.forEach(review -> review.markDelayNotified(now));
        outboxPublisher.publish(new ImageReviewDelayed(delayed.size(), now));
        log.info("관리자 확인 지연({}) 알림 이벤트: {}건", DELAY_AFTER, delayed.size());
        return delayed.size();
    }

    // ── 24시간 기한 ───────────────────────────────

    // 관리자 확인으로 간 지 24시간이 지난 건을 한 건씩(SKIP LOCKED) REVIEW_EXPIRED로 반려. 처리한 건수를 돌려줌
    // 재검수 횟수 · 최근 7일 반려 수에는 세지 않음 (판매자 잘못이 아님)
    public int expireOverdue() {
        int processed = 0;
        while (processed < MAX_EXPIRES_PER_RUN) {
            Boolean handled = transactionTemplate.execute(status -> expireNext());
            if (!Boolean.TRUE.equals(handled)) {
                break;
            }
            processed++;
        }
        return processed;
    }

    private Boolean expireNext() {
        LocalDateTime now = LocalDateTime.now(clock);
        Long id = imageReviewRepository.lockNextExpiredTarget(now.minus(EXPIRE_AFTER)).orElse(null);
        if (id == null) {
            return false;
        }
        ImageReview review = imageReviewRepository.findById(id).orElseThrow();
        if (productRepository.applyReviewResult(review.getProductId(), review.getReviewVersion(),
                ProductStatus.REVIEW_PENDING, ProductStatus.REJECTED) == 0) {
            // 그사이 수정 · 삭제된 상품의 옛 검수. 목록에서도 이미 빠져 있음
            review.markStale();
            log.info("기한 자동 반려 건너뜀(상품이 바뀜): productId={}, reviewVersion={}", review.getProductId(), review.getReviewVersion());
            return true;
        }
        Product product = productRepository.findById(review.getProductId()).orElseThrow();
        review.expire(now);
        reviewResultPublisher.rejected(product.getId(), review.getSellerId(), review.getReviewVersion(), product.getTitle(),
                RejectReason.REVIEW_EXPIRED, null);
        log.info("관리자 확인 기한({}) 초과 자동 반려: productId={}, reviewVersion={}, manualReason={}",
                EXPIRE_AFTER, product.getId(), review.getReviewVersion(), review.getManualReason());
        return true;
    }
}
