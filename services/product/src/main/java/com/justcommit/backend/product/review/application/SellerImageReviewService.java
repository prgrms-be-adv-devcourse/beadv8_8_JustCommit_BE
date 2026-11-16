package com.justcommit.backend.product.review.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.domain.ReviewScope;
import com.justcommit.backend.product.product.event.ProductStatusChanged;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.review.domain.ImageReview;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import com.justcommit.backend.product.review.infrastructure.ImageReviewRepository;
import com.justcommit.backend.product.review.presentation.dto.SellerImageReviewHistoryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// 판매자 검수 API: 검수 기록 조회 · 이의 신청 · 기한 초과 재요청 (docs/flows/image-review.md 「판매자 API」)
// 모두 본인 상품만, 남의 상품 · 삭제된 상품은 404 PRODUCT_NOT_FOUND
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerImageReviewService {

    // 검수 중 동시 5개 (이의 신청 포함, ProductService 등록 제한과 같은 값)
    static final int REVIEW_PENDING_LIMIT = 5;

    private final ImageReviewRepository imageReviewRepository;
    private final ProductRepository productRepository;
    private final ImageReviewService imageReviewService;
    private final OutboxPublisher outboxPublisher;
    private final Clock clock;

    // ── 검수 기록 ───────────────────────────────

    @Transactional(readOnly = true)
    public SellerImageReviewHistoryResponse history(Long memberId, Long productId) {
        Product product = productRepository.findById(productId)
                .filter(p -> p.getStatus() != ProductStatus.DELETED && p.isOwnedBy(memberId))
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
        List<ImageReview> reviews = imageReviewRepository.findByProductIdOrderByReviewVersionDesc(productId);
        ImageReview current = reviews.stream()
                .filter(review -> review.getReviewVersion() == product.getReviewVersion())
                .findFirst().orElse(null);
        boolean appealable = product.getStatus() == ProductStatus.REJECTED
                && current != null && current.isAppealable() && !current.isAppealed();
        boolean resubmittable = product.getStatus() == ProductStatus.REJECTED
                && current != null && current.getRejectReason() == RejectReason.REVIEW_EXPIRED;
        return SellerImageReviewHistoryResponse.of(product, reviews, appealable, resubmittable, LocalDateTime.now(clock),
                AdminImageReviewService.DELAY_AFTER);
    }

    // ── 이의 신청 ───────────────────────────────

    // 자동 반려에만, 같은 검수 버전 1번. 반려 → 검수 중(관리자 확인). 재검수 횟수는 쓰지 않음
    @Transactional
    public void appeal(Long memberId, Long productId, String memo) {
        Product product = ownedProductForUpdate(memberId, productId);
        ImageReview review = currentReview(product);
        if (review != null && review.isAppealed()) {
            throw new BusinessException(ProductErrorCode.APPEAL_ALREADY_USED);
        }
        if (product.getStatus() != ProductStatus.REJECTED || review == null || !review.isAppealable()) {
            throw new BusinessException(ProductErrorCode.APPEAL_NOT_ALLOWED);
        }
        if (productRepository.countBySellerIdAndStatus(product.getSellerId(), ProductStatus.REVIEW_PENDING) >= REVIEW_PENDING_LIMIT) {
            throw new BusinessException(ProductErrorCode.REVIEW_PENDING_LIMIT_EXCEEDED);
        }
        product.startAppeal();
        review.appeal(memo, LocalDateTime.now(clock));
        outboxPublisher.publish(new ProductStatusChanged(product.getId()));
        log.info("이의 신청: productId={}, reviewVersion={}, rejectReason={}",
                product.getId(), review.getReviewVersion(), review.getRejectReason());
    }

    // ── 기한 초과 재요청 ───────────────────────────────

    // 기한 초과(REVIEW_EXPIRED)로 반려된 상품을 같은 내용으로 다시 검수. 재검수 · 등록 제한에 세지 않음
    @Transactional
    public void resubmitAfterExpiry(Long memberId, Long productId) {
        Product product = ownedProductForUpdate(memberId, productId);
        ImageReview review = currentReview(product);
        if (product.getStatus() != ProductStatus.REJECTED || review == null
                || review.getRejectReason() != RejectReason.REVIEW_EXPIRED) {
            throw new BusinessException(ProductErrorCode.RESUBMIT_NOT_ALLOWED);
        }
        product.resubmitAfterExpiry();
        outboxPublisher.publish(new ProductStatusChanged(product.getId()));
        imageReviewService.request(product, ReviewScope.FULL);   // 바로 앞 검수가 기한 초과라 하루 30회를 확인 · 세지 않음
        log.info("기한 초과 재요청: productId={}, reviewVersion={}", product.getId(), product.getReviewVersion());
    }

    private Product ownedProductForUpdate(Long memberId, Long productId) {
        return productRepository.findWithLockById(productId)
                .filter(p -> p.getStatus() != ProductStatus.DELETED && p.isOwnedBy(memberId))
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    private ImageReview currentReview(Product product) {
        return imageReviewRepository.findByProductIdAndReviewVersion(product.getId(), product.getReviewVersion()).orElse(null);
    }
}
