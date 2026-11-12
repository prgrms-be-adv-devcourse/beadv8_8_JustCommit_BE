package com.justcommit.backend.product.review.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.banned.application.BannedListService;
import com.justcommit.backend.product.banned.domain.BannedTextMatcher;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.image.application.ImageData;
import com.justcommit.backend.product.image.application.ImageReader;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.domain.ProductImage;
import com.justcommit.backend.product.product.domain.ReviewScope;
import com.justcommit.backend.product.product.event.ProductReviewRequested;
import com.justcommit.backend.product.product.event.ProductStatusChanged;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.review.domain.ImageReview;
import com.justcommit.backend.product.review.domain.ModerationResult;
import com.justcommit.backend.product.review.domain.PlantIdentification;
import com.justcommit.backend.product.review.domain.ReviewVerdict;
import com.justcommit.backend.product.review.domain.SellerSpecies;
import com.justcommit.backend.product.review.domain.enums.ManualReason;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import com.justcommit.backend.product.review.domain.enums.ReviewDecision;
import com.justcommit.backend.product.review.infrastructure.ImageReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// 사진 검수 (docs/flows/image-review.md)
// 1) 요청: 등록 · 수정 트랜잭션 안에서 검수 기록(REQUESTED)과 검수 요청 이벤트(Outbox → product.review.v1)를 함께 저장
// 2) 실행: 검수 리스너가 부름. 짧은 트랜잭션으로 읽고 → 트랜잭션 밖에서 외부 API → 짧은 트랜잭션으로 조건부 반영
//    외부 API를 기다리는 동안 DB 커넥션 · 행 잠금을 잡지 않으려고 트랜잭션을 나눔
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageReviewService {

    static final int DAILY_REVIEW_LIMIT = 30;          // 판매자당 하루 사진 검수 (한국 시간 자정 기준)
    static final int RECENT_REJECTION_LIMIT = 5;       // 최근 7일 반려가 이만큼이면 자동 검수 없이 관리자 확인
    static final int RECENT_REJECTION_DAYS = 7;

    private final ImageReviewRepository imageReviewRepository;
    private final ProductRepository productRepository;
    private final ImageReader imageReader;
    private final ModerationClient moderationClient;
    private final PlantIdentifier plantIdentifier;
    private final BannedListService bannedListService;
    private final ReviewJudgeProvider judgeProvider;
    private final OutboxPublisher outboxPublisher;
    private final TransactionTemplate transactionTemplate;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    // ── 요청 ───────────────────────────────

    // 등록 · 수정 트랜잭션 안에서만 부름 (상품 저장과 검수 요청이 함께 커밋 · 롤백)
    @Transactional(propagation = Propagation.MANDATORY)
    public void request(Product product, ReviewScope scope) {
        // 사진 검수만 셈. 동시에 요청하면 확인과 저장 사이에 끼어 1~2회 넘을 수 있다 (비용 보호용이라 허용, 등록 제한과 같음)
        if (scope == ReviewScope.FULL) {
            LocalDateTime startOfToday = LocalDate.now(clock).atStartOfDay();
            long today = imageReviewRepository.countBySellerIdAndScopeAndCreatedAtGreaterThanEqual(
                    product.getSellerId(), ReviewScope.FULL, startOfToday);
            if (today >= DAILY_REVIEW_LIMIT) {
                throw new BusinessException(ProductErrorCode.DAILY_REVIEW_LIMIT_EXCEEDED);
            }
        }
        imageReviewRepository.save(ImageReview.requested(product.getId(), product.getReviewVersion(), product.getSellerId(), scope));
        outboxPublisher.publish(new ProductReviewRequested(product.getId(), product.getReviewVersion()));
    }

    // ── 실행 ───────────────────────────────

    public void review(Long productId, int reviewVersion) {
        Target target = transactionTemplate.execute(status -> load(productId, reviewVersion));
        if (target == null) {
            return;
        }
        Outcome outcome = target.scope() == ReviewScope.FULL ? reviewFull(target) : reviewText(target);
        transactionTemplate.executeWithoutResult(status -> apply(target, outcome));
    }

    // 검수할 상품을 읽음. 이미 처리했거나 그사이 상품이 바뀌었으면 null
    private Target load(Long productId, int reviewVersion) {
        ImageReview review = imageReviewRepository.findByProductIdAndReviewVersion(productId, reviewVersion).orElse(null);
        if (review == null || !review.isRequested()) {
            log.info("검수 건너뜀(없거나 이미 처리): productId={}, reviewVersion={}", productId, reviewVersion);
            return null;
        }
        Product product = productRepository.findWithDetailById(productId).orElse(null);
        if (product == null || product.getReviewVersion() != reviewVersion || product.getStatus() != expectedStatus(review.getScope())) {
            review.markStale();
            log.info("검수 건너뜀(상품이 바뀜): productId={}, reviewVersion={}", productId, reviewVersion);
            return null;
        }
        boolean repeatedRejections = review.getScope() == ReviewScope.FULL && recentRejections(product.getSellerId()) >= RECENT_REJECTION_LIMIT;
        return new Target(productId, reviewVersion, review.getScope(), product.getTitle(), product.getDescription(),
                product.getImages().stream().map(ProductImage::getS3Url).toList(),
                product.getSpecies().getScientificName(), repeatedRejections);
    }

    private long recentRejections(Long sellerId) {
        LocalDateTime from = LocalDateTime.now(clock).minusDays(RECENT_REJECTION_DAYS);
        return imageReviewRepository.countBySellerIdAndDecisionAndRejectReasonNotAndReviewedAtGreaterThanEqual(
                sellerId, ReviewDecision.REJECTED, RejectReason.REVIEW_EXPIRED, from);
    }

    // 사진 검수: 유해 검사 → (유해가 아니면) 식물 식별 → 판정
    private Outcome reviewFull(Target target) {
        if (target.repeatedRejections()) {
            return new Outcome(ReviewVerdict.manual(ManualReason.REPEATED_REJECTIONS, null), null, null);
        }
        List<String> errors = new ArrayList<>();
        List<ImageData> images = null;
        try {
            images = target.imageUrls().stream().map(imageReader::read).toList();
        } catch (RuntimeException e) {
            errors.add("사진 읽기 실패: " + e.getMessage());
            log.warn("검수 사진 읽기 실패: productId={}", target.productId(), e);
        }

        ModerationResult moderation = null;
        if (images != null) {
            try {
                boolean imageFlagged = false;
                for (ImageData image : images) {
                    if (moderationClient.isImageFlagged(image)) {
                        imageFlagged = true;
                        break;   // 한 장이라도 유해면 반려라 나머지는 보지 않음
                    }
                }
                moderation = new ModerationResult(imageFlagged, moderationClient.isTextFlagged(text(target)));
            } catch (RuntimeException e) {
                errors.add("Moderation: " + e.getMessage());
                log.warn("Moderation 실패: productId={}", target.productId(), e);
            }
        }

        // 유해로 반려될 상품에는 식물 식별 비용을 쓰지 않음
        PlantIdentification plant = null;
        boolean harmful = moderation != null && (moderation.imageFlagged() || moderation.textFlagged());
        if (images != null && !harmful) {
            try {
                plant = plantIdentifier.identify(images, bannedListService.promptNames());
            } catch (RuntimeException e) {
                errors.add("Haiku: " + e.getMessage());
                log.warn("식물 식별 실패: productId={}", target.productId(), e);
            }
        }

        BannedTextMatcher.Match text = bannedListService.matchText(target.title(), target.description());
        ReviewVerdict verdict = judgeProvider.get().judge(moderation, plant, text, SellerSpecies.of(target.sellerScientificName()));
        return new Outcome(verdict, engineJson(new EngineResult(moderation, plant)), errors.isEmpty() ? null : String.join(" / ", errors));
    }

    // 판매 중 상품의 글만 수정: 글 유해 검사 · 금지어 대조만
    private Outcome reviewText(Target target) {
        Boolean textFlagged = null;
        String error = null;
        try {
            textFlagged = moderationClient.isTextFlagged(text(target));
        } catch (RuntimeException e) {
            error = "Moderation: " + e.getMessage();
            log.warn("Moderation 실패(글): productId={}", target.productId(), e);
        }
        BannedTextMatcher.Match text = bannedListService.matchText(target.title(), target.description());
        ReviewVerdict verdict = judgeProvider.get().judgeText(textFlagged, text);
        ModerationResult moderation = textFlagged == null ? null : ModerationResult.textOnly(textFlagged);
        return new Outcome(verdict, engineJson(new EngineResult(moderation, null)), error);
    }

    // 판정을 상품에 반영. 검수 버전이 같고 기대한 상태일 때만 (그사이 수정 · 삭제 · 예약됐으면 반영하지 않음)
    private void apply(Target target, Outcome outcome) {
        ImageReview review = imageReviewRepository.findByProductIdAndReviewVersion(target.productId(), target.reviewVersion())
                .orElse(null);
        if (review == null || !review.isRequested()) {
            return;   // 같은 요청이 동시에 두 번 처리됨. 먼저 끝난 쪽만 반영
        }
        ProductStatus from = expectedStatus(target.scope());
        ProductStatus to = switch (outcome.verdict().decision()) {
            case APPROVED -> ProductStatus.ACTIVE;
            case REJECTED -> ProductStatus.REJECTED;
            case MANUAL -> ProductStatus.REVIEW_PENDING;   // 판매 중 상품도 관리자가 볼 때까지 판매를 멈춤
        };
        // 상태가 그대로인 경우(사진 검수 관리자 확인 · 글 검사 통과)도 같은 조건으로 UPDATE해 버전이 그대로인지 확인
        if (productRepository.applyReviewResult(target.productId(), target.reviewVersion(), from, to) == 0) {
            review.markStale();
            log.info("검수 결과 반영 안 함(상품이 바뀜): productId={}, reviewVersion={}", target.productId(), target.reviewVersion());
            return;
        }
        review.complete(outcome.verdict(), outcome.engineResult(), outcome.errorMessage(), LocalDateTime.now(clock));
        if (from != to) {
            outboxPublisher.publish(new ProductStatusChanged(target.productId()));
        }
        log.info("검수 완료: productId={}, reviewVersion={}, decision={}, rejectReason={}, manualReason={}",
                target.productId(), target.reviewVersion(), outcome.verdict().decision(),
                outcome.verdict().rejectReason(), outcome.verdict().manualReason());
    }

    // 사진 검수는 검수 중 상품, 글 검사는 판매 중 상품 (Product.update)
    private static ProductStatus expectedStatus(ReviewScope scope) {
        return scope == ReviewScope.FULL ? ProductStatus.REVIEW_PENDING : ProductStatus.ACTIVE;
    }

    private static String text(Target target) {
        return target.description() == null ? target.title() : target.title() + "\n" + target.description();
    }

    private String engineJson(EngineResult result) {
        try {
            return jsonMapper.writeValueAsString(result);
        } catch (RuntimeException e) {
            log.warn("엔진 결과 JSON 변환 실패", e);
            return null;
        }
    }

    // 검수에 필요한 상품 값 (트랜잭션 밖에서 씀)
    private record Target(Long productId, int reviewVersion, ReviewScope scope, String title, String description,
                          List<String> imageUrls, String sellerScientificName, boolean repeatedRejections) {
    }

    private record Outcome(ReviewVerdict verdict, String engineResult, String errorMessage) {
    }

    // image_review.engine_result에 남길 엔진 결과 (관리자 화면 · 기준값 확인용, 실패한 단계는 null)
    record EngineResult(ModerationResult moderation, PlantIdentification plant) {
    }
}
