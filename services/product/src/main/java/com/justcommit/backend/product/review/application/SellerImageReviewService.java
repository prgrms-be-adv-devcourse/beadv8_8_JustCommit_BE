package com.justcommit.backend.product.review.application;

import com.justcommit.backend.product.product.application.ProductEventPublisher;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.domain.ReviewScope;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.review.domain.ImageReview;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import com.justcommit.backend.product.review.infrastructure.ImageReviewRepository;
import com.justcommit.backend.product.product.application.ReviewStatusReader;
import com.justcommit.backend.product.review.presentation.dto.SellerImageReviewHistoryResponse;
import com.justcommit.backend.product.review.presentation.dto.SellerImageReviewHistoryResponse.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 판매자 검수 API: 검수 기록 조회 · 이의 신청 · 기한 초과 재요청 (docs/flows/image-review.md 「판매자 API」)
// 모두 본인 상품만, 남의 상품 · 삭제된 상품은 404 PRODUCT_NOT_FOUND
// 내 상품 목록 · 개수에 붙는 검수 정보(ReviewStatusReader)도 여기서 같은 규칙으로 만든다
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerImageReviewService implements ReviewStatusReader {

    // 검수 중 동시 5개 (이의 신청 포함, ProductService 등록 제한과 같은 값)
    static final int REVIEW_PENDING_LIMIT = 5;

    private final ImageReviewRepository imageReviewRepository;
    private final ProductRepository productRepository;
    private final ImageReviewService imageReviewService;
    private final ProductEventPublisher productEventPublisher;
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
        // 두 가능 여부는 상품 · 검수 상태만 본다. 검수 중 동시 5개는 신청 때 확인(넘으면 409 REVIEW_PENDING_LIMIT_EXCEEDED)
        boolean appealable = product.getStatus() == ProductStatus.REJECTED
                && current != null && current.isAppealable() && !current.isAppealed();
        boolean resubmittable = product.getStatus() == ProductStatus.REJECTED
                && current != null && current.getRejectReason() == RejectReason.REVIEW_EXPIRED;
        return SellerImageReviewHistoryResponse.of(product, reviews, appealable, resubmittable, LocalDateTime.now(clock),
                AdminImageReviewService.DELAY_AFTER, AdminImageReviewService.EXPIRE_AFTER);
    }

    // ── 내 상품 목록 · 개수 (ReviewStatusReader) ───────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public DailyUsage todayPhotoReviews(Long sellerId) {
        return new DailyUsage(imageReviewService.countTodayPhotoReviews(sellerId), ImageReviewService.DAILY_REVIEW_LIMIT);
    }

    // 페이지의 상품 ID를 모아 지금 검수 버전의 기록을 쿼리 한 번으로. 표시 상태 · 반려 문구는 검수 기록 응답과 같은 규칙
    @Override
    @Transactional(readOnly = true)
    public Map<Long, LatestReview> latestReviews(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return imageReviewRepository.findCurrentByProductIdIn(products.stream().map(Product::getId).toList()).stream()
                .collect(Collectors.toMap(ImageReview::getProductId, review -> {
                    Item item = SellerImageReviewHistoryResponse.item(review, now,
                            AdminImageReviewService.DELAY_AFTER, AdminImageReviewService.EXPIRE_AFTER);
                    return new LatestReview(item.status().name(), item.rejectReason(), item.rejectMessage(), item.decidedBy());
                }));
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
        productEventPublisher.statusChanged(product.getId());
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
        productEventPublisher.statusChanged(product.getId());
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
