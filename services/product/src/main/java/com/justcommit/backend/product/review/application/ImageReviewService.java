package com.justcommit.backend.product.review.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.banned.application.BannedListService;
import com.justcommit.backend.product.banned.domain.BannedTextMatcher;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.image.application.ImageData;
import com.justcommit.backend.product.image.application.ImageReader;
import com.justcommit.backend.product.product.application.ReviewRequester;
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
import com.justcommit.backend.product.review.domain.enums.ReviewStatus;
import com.justcommit.backend.product.review.infrastructure.ImageReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// 사진 검수 (docs/flows/image-review.md)
// 1) 요청: 등록 · 수정 트랜잭션 안에서 검수 기록(REQUESTED)과 검수 요청 이벤트(Outbox → product.review.v1)를 함께 저장
// 2) 실행: 검수 리스너가 부름. 짧은 트랜잭션으로 읽고 → 트랜잭션 밖에서 외부 API → 짧은 트랜잭션으로 조건부 반영
//    외부 API를 기다리는 동안 DB 커넥션 · 행 잠금을 잡지 않으려고 트랜잭션을 나눔
// 3) 엔진 오류 재처리: 외부 API가 재시도 후에도 실패하면 상품은 그대로 두고 ENGINE_ERROR,
//    재처리 스케줄러가 5분 뒤 같은 검수 버전으로 다시 검수 (최대 3회, 그래도 실패하면 관리자 확인 ENGINE_ERROR)
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageReviewService implements ReviewRequester {

    static final int DAILY_REVIEW_LIMIT = 30;          // 판매자당 하루 사진 검수 (한국 시간 자정 기준)
    static final int RECENT_REJECTION_LIMIT = 5;       // 최근 7일 반려가 이만큼이면 자동 검수 없이 관리자 확인
    static final int RECENT_REJECTION_DAYS = 7;
    static final int MAX_ENGINE_RETRIES = 3;           // 엔진 오류 재처리 최대 횟수
    static final Duration ENGINE_RETRY_DELAY = Duration.ofMinutes(5);
    static final Duration RETRY_LEASE = Duration.ofMinutes(10);   // 재처리 중인 건을 다른 파드가 다시 집지 않게 미루는 시간
    static final int MAX_RETRIES_PER_RUN = 20;         // 스케줄러 한 번에 처리할 최대 건수
    // 한 건 검수에 새 외부 호출을 시작할 수 있는 시간. 이미 시작한 호출(Moderation 최악 약 80초 · Haiku 약 90초)이 더해져도
    // Kafka max.poll.interval.ms(5분) 안에 끝나게 (리스너는 max.poll.records=1)
    static final Duration REVIEW_TIME_BUDGET = Duration.ofMinutes(2);

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
    @Override
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

    // 검수 리스너: REQUESTED인 검수 기록만 (같은 이벤트가 두 번 와도, 엔진 오류로 재처리 대기 중이어도 다시 하지 않음)
    public void review(Long productId, int reviewVersion) {
        process(productId, reviewVersion, ReviewStatus.REQUESTED);
    }

    // 재처리 스케줄러: 다음 시각이 지난 엔진 오류를 한 건씩 맡아 다시 검수. 처리한 건수를 돌려줌
    // 하루 사진 검수 30회는 검수 기록을 새로 만들 때만 세므로 재처리는 세지 않음
    public int retryEngineErrors() {
        int processed = 0;
        while (processed < MAX_RETRIES_PER_RUN) {
            Claimed claimed = transactionTemplate.execute(status -> claimNextEngineError());
            if (claimed == null) {
                break;
            }
            process(claimed.productId(), claimed.reviewVersion(), ReviewStatus.ENGINE_ERROR);
            processed++;
        }
        return processed;
    }

    // 외부 API 키가 둘 다 있어야 재처리할 의미가 있음 (스케줄러가 헛돌지 않게)
    public boolean isEngineConfigured() {
        return moderationClient.isConfigured() && plantIdentifier.isConfigured();
    }

    private Claimed claimNextEngineError() {
        LocalDateTime now = LocalDateTime.now(clock);
        return imageReviewRepository.lockNextEngineError(now)
                .flatMap(imageReviewRepository::findById)
                .map(review -> {
                    review.startRetry(now.plus(RETRY_LEASE));
                    log.info("엔진 오류 재처리 {}회차: productId={}, reviewVersion={}",
                            review.getRetryCount(), review.getProductId(), review.getReviewVersion());
                    return new Claimed(review.getProductId(), review.getReviewVersion());
                })
                .orElse(null);
    }

    private void process(Long productId, int reviewVersion, ReviewStatus expected) {
        Target target = transactionTemplate.execute(status -> load(productId, reviewVersion, expected));
        if (target == null) {
            return;
        }
        Outcome outcome = target.scope() == ReviewScope.FULL ? reviewFull(target) : reviewText(target);
        transactionTemplate.executeWithoutResult(status -> apply(target, outcome, expected));
    }

    // 검수할 상품을 읽음. 검수 기록이 기대한 상태가 아니거나(이미 처리) 그사이 상품이 바뀌었으면 null
    private Target load(Long productId, int reviewVersion, ReviewStatus expected) {
        ImageReview review = imageReviewRepository.findByProductIdAndReviewVersion(productId, reviewVersion).orElse(null);
        if (review == null || review.getStatus() != expected) {
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
        Instant deadline = clock.instant().plus(REVIEW_TIME_BUDGET);
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
                    checkTimeBudget(deadline);
                    if (moderationClient.isImageFlagged(image)) {
                        imageFlagged = true;
                        break;   // 한 장이라도 유해면 반려라 나머지는 보지 않음
                    }
                }
                checkTimeBudget(deadline);
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
                checkTimeBudget(deadline);
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
    private void apply(Target target, Outcome outcome, ReviewStatus expected) {
        ImageReview review = imageReviewRepository.findByProductIdAndReviewVersion(target.productId(), target.reviewVersion())
                .orElse(null);
        if (review == null || review.getStatus() != expected) {
            return;   // 같은 요청이 동시에 두 번 처리됨. 먼저 끝난 쪽만 반영
        }
        ProductStatus from = expectedStatus(target.scope());
        if (isEngineError(outcome.verdict()) && canRetry(review)) {
            // 상품 상태는 그대로. 같은 조건으로 UPDATE해 그사이 버전이 바뀌지 않았는지만 확인
            if (productRepository.applyReviewResult(target.productId(), target.reviewVersion(), from, from) == 0) {
                review.markStale();
                return;
            }
            review.markEngineError(outcome.engineResult(), outcome.errorMessage(), LocalDateTime.now(clock).plus(ENGINE_RETRY_DELAY));
            log.warn("검수 엔진 오류, {} 뒤 재처리 (재처리 {}/{}회): productId={}, reviewVersion={}, error={}",
                    ENGINE_RETRY_DELAY, review.getRetryCount(), MAX_ENGINE_RETRIES,
                    target.productId(), target.reviewVersion(), outcome.errorMessage());
            return;
        }
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

    private static boolean isEngineError(ReviewVerdict verdict) {
        return verdict.decision() == ReviewDecision.MANUAL && verdict.manualReason() == ManualReason.ENGINE_ERROR;
    }

    // 재처리 횟수가 남았고 API 키가 있을 때만 (키가 없으면 다시 해도 실패하므로 바로 관리자 확인)
    private boolean canRetry(ImageReview review) {
        return review.getRetryCount() < MAX_ENGINE_RETRIES && isEngineConfigured();
    }

    private void checkTimeBudget(Instant deadline) {
        if (clock.instant().isAfter(deadline)) {
            throw new ReviewEngineException("검수 시간 상한(" + REVIEW_TIME_BUDGET + ") 초과");
        }
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

    private record Claimed(Long productId, int reviewVersion) {
    }

    private record Outcome(ReviewVerdict verdict, String engineResult, String errorMessage) {
    }

    // image_review.engine_result에 남길 엔진 결과 (관리자 화면 · 기준값 확인용, 실패한 단계는 null)
    record EngineResult(ModerationResult moderation, PlantIdentification plant) {
    }
}
