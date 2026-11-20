package com.justcommit.backend.product.review.application;

import com.justcommit.backend.product.product.application.ProductEventPublisher;
import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.banned.application.BannedListService;
import com.justcommit.backend.product.config.ClockConfig;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.image.application.ImageReader;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.domain.ReviewScope;
import com.justcommit.backend.product.product.event.ProductReviewRequested;
import com.justcommit.backend.product.product.event.ProductStatusChanged;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.review.domain.ImageReview;
import com.justcommit.backend.product.review.domain.ReviewVerdict;
import com.justcommit.backend.product.review.domain.enums.ManualReason;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import com.justcommit.backend.product.review.domain.enums.ReviewDecision;
import com.justcommit.backend.product.review.domain.enums.ReviewStatus;
import com.justcommit.backend.product.review.infrastructure.ImageReviewRepository;
import com.justcommit.backend.product.review.presentation.dto.SellerImageReviewHistoryResponse;
import com.justcommit.backend.product.review.presentation.dto.SellerImageReviewHistoryResponse.DisplayStatus;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.infrastructure.SpeciesRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import com.justcommit.backend.product.client.member.MemberQuery;
import com.justcommit.backend.product.product.application.ReviewStatusReader;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 이의 신청 · 재검수 2회 · 기한 초과 재요청 · 판매자 검수 기록 (#467, docs/flows/image-review.md)
// 실제 PostgreSQL(V10 · V12), 외부 API와 Outbox 발행만 가짜
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class, SellerImageReviewService.class, ImageReviewService.class,
        AdminImageReviewService.class, ReviewJudgeProvider.class, ReviewResultPublisher.class, ProductEventPublisher.class, BannedListService.class,
        SellerImageReviewServiceIntegrationTest.Config.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SellerImageReviewServiceIntegrationTest {

    private static final Long SELLER_ID = 7L;
    private static final Long OTHER_SELLER_ID = 8L;
    private static final Long ADMIN_ID = 1L;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @TestConfiguration
    static class Config {
        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    // 서비스와 같은 한국 시간 Clock으로 시각을 만든다 (JVM · DB 시간대가 UTC인 CI에서도 같은 기준)
    @Autowired Clock clock;

    @Autowired SellerImageReviewService sellerService;
    @Autowired ImageReviewService imageReviewService;
    @Autowired AdminImageReviewService adminService;
    @Autowired ImageReviewRepository imageReviewRepository;
    @Autowired ProductRepository productRepository;
    @Autowired SpeciesRepository speciesRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean ImageReader imageReader;
    @MockitoBean ModerationClient moderationClient;
    @MockitoBean PlantIdentifier plantIdentifier;
    @MockitoBean OutboxPublisher outboxPublisher;
    @MockitoBean MemberQuery memberQuery;   // AdminImageReviewService가 씀 (관리자 목록 닉네임)

    private Species species;

    @BeforeEach
    void setUp() {
        species = BeanUtils.instantiateClass(Species.class);
        ReflectionTestUtils.setField(species, "name", "판매자 검수 테스트 품종");
        ReflectionTestUtils.setField(species, "scientificName", "Monstera deliciosa");
        speciesRepository.save(species);
    }

    @AfterEach
    void cleanUp() {
        imageReviewRepository.deleteAllInBatch();
        jdbc.update("delete from product_image");
        productRepository.deleteAllInBatch();
        speciesRepository.deleteById(species.getId());
    }

    // ── 준비 ───────────────────────────────

    // 검수 중 상품 + 지금 검수 버전의 검수 기록을 엔진이 verdict로 판정한 상태. 반려면 상품도 REJECTED
    private Product judged(ReviewVerdict verdict) {
        return tx.execute(status -> {
            Product product = productRepository.save(Product.create(species, SELLER_ID, "몬스테라", null,
                    new BigDecimal("10000"), null, null, null, List.of("http://localhost:9000/bucket/products/7/a.jpg")));
            completeCurrent(product, verdict);
            return product;
        });
    }

    private void completeCurrent(Product product, ReviewVerdict verdict) {
        ImageReview review = ImageReview.requested(product.getId(), product.getReviewVersion(), SELLER_ID, ReviewScope.FULL);
        review.complete(verdict, null, null, LocalDateTime.now(clock));
        imageReviewRepository.save(review);
        if (verdict.decision() == ReviewDecision.REJECTED) {
            productRepository.applyReviewResult(product.getId(), product.getReviewVersion(),
                    ProductStatus.REVIEW_PENDING, ProductStatus.REJECTED);
        }
    }

    private Product rejectedByEngine() {
        return judged(ReviewVerdict.reject(RejectReason.BANNED_SPECIES, "나도풍란"));
    }

    // 관리자 확인으로 갔다가 24시간이 지나 REVIEW_EXPIRED
    private Product expired() {
        Product product = judged(ReviewVerdict.manual(ManualReason.SPECIES_MISMATCH, "Epipremnum aureum"));
        jdbc.update("update image_review set reviewed_at = ? where product_id = ?", LocalDateTime.now(clock).minusHours(25), product.getId());
        assertThat(adminService.expireOverdue()).isEqualTo(1);
        return product;
    }

    // 판매자가 사진을 바꿔 수정 (ProductService.updateProduct와 같은 순서: 재검수 확인 → 수정 → 검수 요청)
    private void edit(Product product) {
        tx.executeWithoutResult(status -> {
            Product locked = productRepository.findWithLockById(product.getId()).orElseThrow();
            if (imageReviewService.isResubmit(locked)) {
                locked.useResubmit();
            }
            ReviewScope scope = locked.update(species, "새 제목", null, new BigDecimal("10000"), null, null, null,
                    List.of("http://localhost:9000/bucket/products/7/" + locked.getReviewVersion() + "b.jpg"));
            imageReviewService.request(locked, scope);
        });
    }

    // 지금 검수 버전을 엔진이 반려했다고 표시
    private void engineRejectsCurrent(Product product) {
        tx.executeWithoutResult(status -> {
            Product current = productRepository.findById(product.getId()).orElseThrow();
            ImageReview review = imageReviewRepository.findByProductIdAndReviewVersion(current.getId(), current.getReviewVersion()).orElseThrow();
            review.complete(ReviewVerdict.reject(RejectReason.NOT_PLANT, null), null, null, LocalDateTime.now(clock));
            productRepository.applyReviewResult(current.getId(), current.getReviewVersion(),
                    ProductStatus.REVIEW_PENDING, ProductStatus.REJECTED);
        });
    }

    private Product reload(Product product) {
        return productRepository.findById(product.getId()).orElseThrow();
    }

    // payload v3 (#456, ADR 0031): 반영 직후 DB 값(새 상태 · 새 상품 버전 · 상품 문서 값)이 그대로 담겨야 함
    // 대표 사진까지 읽으려고 사진을 함께 가져옴 (세션 밖에서 지연 로딩 안 됨)
    private ProductStatusChanged statusChangedOf(Product product) {
        Product reloaded = productRepository.findWithDetailById(product.getId()).orElseThrow();
        return new ProductStatusChanged(reloaded.getId(), reloaded.getSpecies().getId(), reloaded.getSellerId(),
                reloaded.getStatus().name(), reloaded.isHidden(), reloaded.getVersion(), reloaded.getTitle(),
                reloaded.getPrice(), reloaded.getThumbnailUrl(), reloaded.getDescription(), reloaded.getGrowNote(),
                reloaded.getHeightCm(), reloaded.getPotSize());
    }

    private ImageReview reviewOf(Product product, int version) {
        return imageReviewRepository.findByProductIdAndReviewVersion(product.getId(), version).orElseThrow();
    }

    private void assertCode(Runnable action, ProductErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(code);
    }

    // ── 이의 신청 ───────────────────────────────

    @Test
    @DisplayName("[S-C06] 자동 반려에 이의 신청하면 검수 중 · 관리자 확인으로 가고, 원래 반려 사유와 메모가 관리자 목록에 보임")
    void appeal() {
        Product product = rejectedByEngine();

        sellerService.appeal(SELLER_ID, product.getId(), "직접 키운 호접란입니다. 꽃대 사진을 보시면 압니다");

        Product reloaded = reload(product);
        assertThat(reloaded.getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
        assertThat(reloaded.getReviewVersion()).isZero();   // 같은 검수 버전
        assertThat(reloaded.remainingResubmits()).isEqualTo(Product.MAX_RESUBMITS);   // 재검수 횟수를 쓰지 않음
        ImageReview review = reviewOf(product, 0);
        assertThat(review.getDecision()).isEqualTo(ReviewDecision.MANUAL);
        assertThat(review.getManualReason()).isEqualTo(ManualReason.APPEAL);
        assertThat(review.getRejectReason()).isEqualTo(RejectReason.BANNED_SPECIES);
        assertThat(review.getDecidedBy()).isNull();
        verify(outboxPublisher).publish(statusChangedOf(product));

        assertThat(adminService.findAwaiting(PageRequest.of(0, 20)).getContent()).singleElement().satisfies(row -> {
            assertThat(row.appealMemo()).isEqualTo("직접 키운 호접란입니다. 꽃대 사진을 보시면 압니다");
            assertThat(row.rejectReason()).isEqualTo("BANNED_SPECIES");
            assertThat(row.manualReason()).isEqualTo("APPEAL");
        });
    }

    @Test
    @DisplayName("[S-C06] 이의 신청은 같은 검수 버전으로 1번: 관리자가 다시 반려하면 두 번째는 409 APPEAL_ALREADY_USED")
    void appealOnce() {
        Product product = rejectedByEngine();
        sellerService.appeal(SELLER_ID, product.getId(), "다시 봐 주세요");
        assertCode(() -> sellerService.appeal(SELLER_ID, product.getId(), "한 번 더"), ProductErrorCode.APPEAL_ALREADY_USED);

        adminService.decide(reviewOf(product, 0).getId(), ADMIN_ID, ReviewDecision.REJECTED, RejectReason.BANNED_SPECIES, null);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REJECTED);
        assertCode(() -> sellerService.appeal(SELLER_ID, product.getId(), "한 번 더"), ProductErrorCode.APPEAL_ALREADY_USED);
    }

    @Test
    @DisplayName("[S-C06] 자동 반려 → 이의 신청 → 관리자 통과: 같은 검수 버전으로 ACTIVE, 재검수 횟수는 그대로")
    void appealThenAdminApproves() {
        Product product = rejectedByEngine();
        sellerService.appeal(SELLER_ID, product.getId(), "직접 키운 호접란입니다");

        adminService.decide(reviewOf(product, 0).getId(), ADMIN_ID, ReviewDecision.APPROVED, null, null);

        Product reloaded = reload(product);
        assertThat(reloaded.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(reloaded.getReviewVersion()).isZero();
        assertThat(reloaded.remainingResubmits()).isEqualTo(Product.MAX_RESUBMITS);
        ImageReview review = reviewOf(product, 0);
        assertThat(review.getDecision()).isEqualTo(ReviewDecision.APPROVED);
        assertThat(review.getManualReason()).isEqualTo(ManualReason.APPEAL);
        assertThat(review.getRejectReason()).isNull();
        assertThat(adminService.findAwaiting(PageRequest.of(0, 20)).getContent()).isEmpty();
    }

    @Test
    @DisplayName("[S-C06] 관리자 반려 · 기한 초과 반려 · 반려가 아닌 상품은 이의 신청 409 APPEAL_NOT_ALLOWED")
    void appealNotAllowed() {
        Product byAdmin = judged(ReviewVerdict.manual(ManualReason.SPECIES_MISMATCH, null));
        adminService.decide(reviewOf(byAdmin, 0).getId(), ADMIN_ID, ReviewDecision.REJECTED, RejectReason.UNCLEAR_IMAGE, null);
        Product byDeadline = expired();
        Product inReview = judged(ReviewVerdict.manual(ManualReason.LOW_CONFIDENCE, null));

        for (Product product : List.of(byAdmin, byDeadline, inReview)) {
            assertCode(() -> sellerService.appeal(SELLER_ID, product.getId(), "메모"), ProductErrorCode.APPEAL_NOT_ALLOWED);
        }
    }

    @Test
    @DisplayName("이의 신청도 검수 중 동시 5개에 셈: 이미 5개면 409 REVIEW_PENDING_LIMIT_EXCEEDED, 남의 상품은 404")
    void appealLimitAndOwner() {
        Product product = rejectedByEngine();
        assertCode(() -> sellerService.appeal(OTHER_SELLER_ID, product.getId(), "메모"), ProductErrorCode.PRODUCT_NOT_FOUND);

        for (int i = 0; i < SellerImageReviewService.REVIEW_PENDING_LIMIT; i++) {
            judged(ReviewVerdict.manual(ManualReason.LOW_CONFIDENCE, null));
        }
        assertCode(() -> sellerService.appeal(SELLER_ID, product.getId(), "메모"), ProductErrorCode.REVIEW_PENDING_LIMIT_EXCEEDED);
        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REJECTED);
    }

    // ── 재검수 2회 ───────────────────────────────

    @Test
    @DisplayName("[S-C11] 반려된 상품을 고치면 재검수 1회씩, 2번 다 쓰면 세 번째 수정은 409 RESUBMIT_LIMIT_EXCEEDED (수정도 롤백)")
    void resubmitTwice() {
        Product product = rejectedByEngine();

        edit(product);                     // 1회
        engineRejectsCurrent(product);
        edit(product);                     // 2회
        engineRejectsCurrent(product);

        assertThat(reload(product).remainingResubmits()).isZero();
        assertCode(() -> edit(product), ProductErrorCode.RESUBMIT_LIMIT_EXCEEDED);
        Product reloaded = reload(product);
        assertThat(reloaded.getStatus()).isEqualTo(ProductStatus.REJECTED);
        assertThat(reloaded.getReviewVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("[S-C11] 이의 신청 중에 고치면 이의 신청은 끝나고(관리자 목록에서 빠짐) 엔진 검수로 가며 재검수 1회를 씀")
    void editDuringAppeal() {
        Product product = rejectedByEngine();
        sellerService.appeal(SELLER_ID, product.getId(), "다시 봐 주세요");

        edit(product);

        Product reloaded = reload(product);
        assertThat(reloaded.getReviewVersion()).isEqualTo(1);
        assertThat(reloaded.remainingResubmits()).isEqualTo(Product.MAX_RESUBMITS - 1);
        assertThat(reviewOf(product, 1).getStatus()).isEqualTo(ReviewStatus.REQUESTED);
        assertThat(adminService.findAwaiting(PageRequest.of(0, 20)).getContent()).isEmpty();
    }

    @Test
    @DisplayName("[S-C11] 검수 중 · 판매 중 상품의 수정과 기한 초과 뒤 수정은 재검수에 세지 않음")
    void notCountedAsResubmit() {
        Product inReview = judged(ReviewVerdict.manual(ManualReason.LOW_CONFIDENCE, null));
        Product approved = judged(ReviewVerdict.approve());
        tx.executeWithoutResult(status -> productRepository.applyReviewResult(approved.getId(), 0,
                ProductStatus.REVIEW_PENDING, ProductStatus.ACTIVE));
        Product expired = expired();

        for (Product product : List.of(inReview, approved, expired)) {
            edit(product);
            assertThat(reload(product).remainingResubmits()).isEqualTo(Product.MAX_RESUBMITS);
        }
    }

    // ── 기한 초과 재요청 ───────────────────────────────

    @Test
    @DisplayName("기한 초과 반려는 같은 내용으로 다시 검수 요청: 검수 버전 +1 · 검수 요청 이벤트, 재검수 · 하루 30회에 세지 않음")
    void resubmitAfterExpiry() {
        Product product = expired();
        // 오늘 이미 하루 사진 검수 30회를 채움
        for (int i = 0; i < ImageReviewService.DAILY_REVIEW_LIMIT; i++) {
            imageReviewRepository.save(ImageReview.requested(1000L + i, 0, SELLER_ID, ReviewScope.FULL));
        }

        sellerService.resubmitAfterExpiry(SELLER_ID, product.getId());

        Product reloaded = reload(product);
        assertThat(reloaded.getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
        assertThat(reloaded.getReviewVersion()).isEqualTo(1);
        assertThat(reloaded.remainingResubmits()).isEqualTo(Product.MAX_RESUBMITS);
        assertThat(reviewOf(product, 1).getStatus()).isEqualTo(ReviewStatus.REQUESTED);
        verify(outboxPublisher).publish(new ProductReviewRequested(product.getId(), 1));
        // 기한 초과 바로 다음 검수는 하루 횟수에 세지 않음 (채워 둔 30회 + 처음 검수 1회만)
        assertThat(imageReviewRepository.countDailyPhotoReviews(SELLER_ID, LocalDate.now().atStartOfDay()))
                .isEqualTo(ImageReviewService.DAILY_REVIEW_LIMIT + 1);
    }

    @Test
    @DisplayName("기한 초과가 아닌 상품은 재요청 409 RESUBMIT_NOT_ALLOWED")
    void resubmitNotAllowed() {
        Product product = rejectedByEngine();

        assertCode(() -> sellerService.resubmitAfterExpiry(SELLER_ID, product.getId()), ProductErrorCode.RESUBMIT_NOT_ALLOWED);
        verify(outboxPublisher, never()).publish(any(ProductReviewRequested.class));
    }

    // ── 검수 기록 ───────────────────────────────

    @Test
    @DisplayName("검수 기록: 최신 버전부터 표시 상태 · 반려 문구 · 판정 주체, 남은 재검수 · 이의 신청 가능 여부")
    void history() {
        Product product = rejectedByEngine();      // 0: 엔진 반려
        edit(product);                             // 1: 검수 중 (재검수 1회 씀)

        SellerImageReviewHistoryResponse before = sellerService.history(SELLER_ID, product.getId());
        assertThat(before.remainingResubmits()).isEqualTo(1);
        assertThat(before.appealable()).isFalse();
        assertThat(before.reviews()).extracting(SellerImageReviewHistoryResponse.Item::reviewVersion).containsExactly(1, 0);
        assertThat(before.reviews().get(0).status()).isEqualTo(DisplayStatus.IN_REVIEW);
        assertThat(before.reviews().get(1).status()).isEqualTo(DisplayStatus.REJECTED);
        assertThat(before.reviews().get(1).rejectMessage()).isEqualTo(RejectReason.BANNED_SPECIES.getMessage());
        assertThat(before.reviews().get(1).decidedBy()).isEqualTo("ENGINE");

        engineRejectsCurrent(product);
        SellerImageReviewHistoryResponse after = sellerService.history(SELLER_ID, product.getId());
        assertThat(after.appealable()).isTrue();
        assertThat(after.resubmittable()).isFalse();

        assertCode(() -> sellerService.history(OTHER_SELLER_ID, product.getId()), ProductErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("검수 기록: 관리자 확인 12시간이 지나면 확인 지연, 기한 초과면 재요청 가능")
    void historyDelayedAndExpired() {
        Product waiting = judged(ReviewVerdict.manual(ManualReason.SPECIES_MISMATCH, null));
        jdbc.update("update image_review set reviewed_at = ? where product_id = ?", LocalDateTime.now(clock).minusHours(13), waiting.getId());
        assertThat(sellerService.history(SELLER_ID, waiting.getId()).reviews().getFirst().status())
                .isEqualTo(DisplayStatus.ADMIN_CHECK_DELAYED);

        Product expired = expired();
        SellerImageReviewHistoryResponse history = sellerService.history(SELLER_ID, expired.getId());
        assertThat(history.resubmittable()).isTrue();
        assertThat(history.appealable()).isFalse();
        assertThat(history.reviews().getFirst().decidedBy()).isEqualTo("DEADLINE");
    }

    // ── #519 판매자 화면 응답 ───────────────────────────────

    @Test
    @DisplayName("검수 기록 expiresAt: 관리자 확인 중일 때만, 이의 신청은 신청 시각부터 24시간이고 관리자 목록과 같은 값")
    void historyExpiresAt() {
        Product product = rejectedByEngine();
        assertThat(sellerService.history(SELLER_ID, product.getId()).reviews().getFirst().expiresAt()).isNull();

        sellerService.appeal(SELLER_ID, product.getId(), "직접 키운 식물입니다");

        SellerImageReviewHistoryResponse.Item item = sellerService.history(SELLER_ID, product.getId()).reviews().getFirst();
        assertThat(item.status()).isEqualTo(DisplayStatus.ADMIN_CHECK);
        assertThat(item.expiresAt()).isEqualTo(reviewOf(product, 0).getAppealedAt().plusHours(24));
        assertThat(adminService.findAwaiting(PageRequest.of(0, 20)).getContent().getFirst().expiresAt())
                .isEqualTo(item.expiresAt());
    }

    @Test
    @DisplayName("내 상품 목록 검수 정보: 지금 검수 버전의 표시 상태 · 반려 사유와 문구 · 판정 주체, 기록이 없는 상품은 빠짐")
    void latestReviews() {
        Product rejected = rejectedByEngine();                                          // 0: 엔진 반려
        Product edited = rejectedByEngine();
        edit(edited);                                                                   // 1: 검수 중 (0은 반려)
        Product delayed = judged(ReviewVerdict.manual(ManualReason.SPECIES_MISMATCH, null));
        jdbc.update("update image_review set reviewed_at = ? where product_id = ?", LocalDateTime.now(clock).minusHours(13), delayed.getId());
        Product noReview = tx.execute(status -> productRepository.save(Product.create(species, SELLER_ID, "기록 없음", null,
                new BigDecimal("10000"), null, null, null, List.of("http://localhost:9000/bucket/products/7/n.jpg"))));

        var reviews = sellerService.latestReviews(List.of(reload(rejected), reload(edited), reload(delayed), noReview));

        assertThat(reviews).containsOnlyKeys(rejected.getId(), edited.getId(), delayed.getId());
        assertThat(reviews.get(rejected.getId())).isEqualTo(new ReviewStatusReader.LatestReview(
                "REJECTED", "BANNED_SPECIES", RejectReason.BANNED_SPECIES.getMessage(), "ENGINE"));
        // 옛 버전(반려)이 아니라 지금 버전(검수 중)
        assertThat(reviews.get(edited.getId())).isEqualTo(new ReviewStatusReader.LatestReview("IN_REVIEW", null, null, null));
        assertThat(reviews.get(delayed.getId()).status()).isEqualTo("ADMIN_CHECK_DELAYED");
        assertThat(sellerService.latestReviews(List.of())).isEmpty();
    }

    @Test
    @DisplayName("오늘 사진 검수 횟수: 한도 확인과 같은 기준 (한국 시간 자정부터, 글 검사 · 기한 초과 다음 검수 제외), 한도 30")
    void todayPhotoReviews() {
        rejectedByEngine();                                                             // 사진 검수 1
        Product expired = expired();                                                    // 사진 검수 2
        sellerService.resubmitAfterExpiry(SELLER_ID, expired.getId());                  // 기한 초과 다음 검수: 세지 않음
        tx.executeWithoutResult(status ->                                               // 글 검사: 세지 않음
                imageReviewRepository.save(ImageReview.requested(2000L, 0, SELLER_ID, ReviewScope.TEXT_ONLY)));
        tx.executeWithoutResult(status ->                                               // 어제: 세지 않음
                imageReviewRepository.save(ImageReview.requested(2001L, 0, SELLER_ID, ReviewScope.FULL)));
        jdbc.update("update image_review set created_at = ? where product_id = ?",
                LocalDate.now(clock).atStartOfDay().minusSeconds(1), 2001L);
        // 나머지는 한국 시간 "지금"으로 맞춤 (JVM 시간대가 UTC여도 같은 결과)
        jdbc.update("update image_review set created_at = ? where product_id <> ?", LocalDateTime.now(clock), 2001L);

        assertThat(sellerService.todayPhotoReviews(SELLER_ID)).isEqualTo(new ReviewStatusReader.DailyUsage(2, 30));
        assertThat(sellerService.todayPhotoReviews(OTHER_SELLER_ID).used()).isZero();
    }
}
