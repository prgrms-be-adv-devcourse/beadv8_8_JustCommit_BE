package com.justcommit.backend.product.review.application;

import com.justcommit.backend.product.product.application.ProductEventPublisher;
import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.banned.application.BannedListService;
import com.justcommit.backend.product.config.ClockConfig;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.image.application.ImageData;
import com.justcommit.backend.product.image.application.ImageReader;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.domain.ReviewScope;
import com.justcommit.backend.product.product.event.ProductApproved;
import com.justcommit.backend.product.product.event.ProductRejected;
import com.justcommit.backend.product.product.event.ProductReviewRequested;
import com.justcommit.backend.product.product.event.ProductStatusChanged;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.review.domain.Candidate;
import com.justcommit.backend.product.review.domain.ImageReview;
import com.justcommit.backend.product.review.domain.PlantIdentification;
import com.justcommit.backend.product.review.domain.ReviewVerdict;
import com.justcommit.backend.product.review.domain.enums.ManualReason;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import com.justcommit.backend.product.review.domain.enums.ReviewDecision;
import com.justcommit.backend.product.review.domain.enums.ReviewStatus;
import com.justcommit.backend.product.review.domain.enums.SaleForm;
import com.justcommit.backend.product.review.infrastructure.ImageReviewRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 검수 요청 → 실행 → 조건부 반영을 실제 PostgreSQL(V8 금지 목록 · V10 검수 기록)로 확인
// 외부 API(사진 읽기 · Moderation · Haiku)와 Outbox 발행만 가짜
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class, ImageReviewService.class, ReviewJudgeProvider.class, ReviewResultPublisher.class, ProductEventPublisher.class,
        BannedListService.class, ImageReviewServiceIntegrationTest.Config.class})
@Testcontainers(disabledWithoutDocker = true)
// 서비스가 트랜잭션을 나눠 쓰므로 테스트 메서드는 트랜잭션으로 감싸지 않음
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ImageReviewServiceIntegrationTest {

    private static final Long SELLER_ID = 7L;
    private static final String MONSTERA = "Monstera deliciosa";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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

    @Autowired ImageReviewService imageReviewService;
    @Autowired ImageReviewRepository imageReviewRepository;
    @Autowired ProductRepository productRepository;
    @Autowired SpeciesRepository speciesRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean ImageReader imageReader;
    @MockitoBean ModerationClient moderationClient;
    @MockitoBean PlantIdentifier plantIdentifier;
    @MockitoBean OutboxPublisher outboxPublisher;

    private Species species;

    @BeforeEach
    void setUp() {
        species = BeanUtils.instantiateClass(Species.class);
        ReflectionTestUtils.setField(species, "name", "검수 테스트 품종");   // V4 품종과 이름이 겹치지 않게
        ReflectionTestUtils.setField(species, "scientificName", MONSTERA);
        speciesRepository.save(species);

        given(imageReader.read(anyString())).willReturn(new ImageData(new byte[]{1}, "image/jpeg"));
        given(moderationClient.isImageFlagged(any())).willReturn(false);
        given(moderationClient.isTextFlagged(anyString())).willReturn(false);
    }

    @AfterEach
    void cleanUp() {
        imageReviewRepository.deleteAllInBatch();
        jdbc.update("delete from product_image");
        productRepository.deleteAllInBatch();
        speciesRepository.deleteById(species.getId());
    }

    private void identifyAs(String scientificName, double confidence) {
        // willReturn 먼저: 앞에서 예외를 던지게 해 둔 목도 다시 정할 수 있게
        willReturn(new PlantIdentification(true, SaleForm.PLANT, List.of(new Candidate(scientificName, null, confidence))))
                .given(plantIdentifier).identify(anyList(), anyList());
    }

    // 등록처럼: 상품 저장 + 검수 요청을 한 트랜잭션에서
    private Product register(String title, String description) {
        return register(species, title, description);
    }

    private Product register(Species species, String title, String description) {
        return tx.execute(status -> {
            Product product = productRepository.save(Product.create(species, SELLER_ID, title, description,
                    new BigDecimal("10000"), null, null, null, List.of("http://localhost:9000/bucket/products/7/a.jpg")));
            imageReviewService.request(product, ReviewScope.FULL);
            return product;
        });
    }

    private Product reload(Product product) {
        return productRepository.findById(product.getId()).orElseThrow();
    }

    private ImageReview reviewOf(Product product, int version) {
        return imageReviewRepository.findByProductIdAndReviewVersion(product.getId(), version).orElseThrow();
    }

    @Test
    @DisplayName("요청: 검수 기록을 REQUESTED로 남기고 검수 요청 이벤트를 같은 트랜잭션에 저장")
    void request() {
        Product product = register("몬스테라 팝니다", null);

        ImageReview review = reviewOf(product, 0);
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.REQUESTED);
        assertThat(review.getScope()).isEqualTo(ReviewScope.FULL);
        verify(outboxPublisher).publish(new ProductReviewRequested(product.getId(), 0));
    }

    @Test
    @DisplayName("요청: 하루 사진 검수 30회를 넘으면 429 DAILY_REVIEW_LIMIT_EXCEEDED, 상품도 저장하지 않음")
    void dailyLimit() {
        for (int i = 0; i < ImageReviewService.DAILY_REVIEW_LIMIT; i++) {
            imageReviewRepository.save(ImageReview.requested(1000L + i, 0, SELLER_ID, ReviewScope.FULL));
        }

        assertThatThrownBy(() -> register("몬스테라 팝니다", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.DAILY_REVIEW_LIMIT_EXCEEDED);
        assertThat(productRepository.count()).isZero();
    }

    @Test
    @DisplayName("통과: 품종이 맞으면 ACTIVE, 검수 기록 DONE, 상태 변경 이벤트")
    void approve() {
        identifyAs(MONSTERA, 0.95);
        Product product = register("몬스테라 팝니다", "잘 키웠어요");

        imageReviewService.review(product.getId(), 0);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.ACTIVE);
        ImageReview review = reviewOf(product, 0);
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.DONE);
        assertThat(review.getDecision()).isEqualTo(ReviewDecision.APPROVED);
        assertThat(review.getEngineResult()).contains("Monstera deliciosa");
        verify(outboxPublisher).publish(any(ProductStatusChanged.class));
        // 판매자 알림용 통과 이벤트 (#345)
        verify(outboxPublisher).publish(new ProductApproved(product.getId(), SELLER_ID, 0, "몬스테라 팝니다"));
        verify(outboxPublisher, never()).publish(any(ProductRejected.class));
    }

    @Test
    @DisplayName("반려: 1순위가 금지 품종(V8, 옛 학명)이고 0.7 이상이면 REJECTED · BANNED_SPECIES")
    void rejectBanned() {
        identifyAs("Phalaenopsis japonica", 0.9);
        Product product = register("난초 팝니다", null);

        imageReviewService.review(product.getId(), 0);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REJECTED);
        ImageReview review = reviewOf(product, 0);
        assertThat(review.getRejectReason()).isEqualTo(RejectReason.BANNED_SPECIES);
        assertThat(review.getDetail()).isEqualTo("나도풍란");
        // 판매자 알림용 반려 이벤트: 사유 코드 · 판매자 문구 (#345)
        verify(outboxPublisher).publish(any(ProductStatusChanged.class));
        verify(outboxPublisher).publish(new ProductRejected(product.getId(), SELLER_ID, 0, "난초 팝니다",
                "BANNED_SPECIES", "판매 금지 품종이에요"));
        verify(outboxPublisher, never()).publish(any(ProductApproved.class));
    }

    @Test
    @DisplayName("반려: 식물이 아니면 REJECTED · NOT_PLANT, 반려 이벤트")
    void rejectNotPlant() {
        willReturn(new PlantIdentification(false, null, List.of()))
                .given(plantIdentifier).identify(anyList(), anyList());
        Product product = register("몬스테라 팝니다", null);

        imageReviewService.review(product.getId(), 0);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REJECTED);
        assertThat(reviewOf(product, 0).getRejectReason()).isEqualTo(RejectReason.NOT_PLANT);
        verify(outboxPublisher).publish(new ProductRejected(product.getId(), SELLER_ID, 0, "몬스테라 팝니다",
                "NOT_PLANT", "식물 사진이 아니에요"));
    }

    @Test
    @DisplayName("반려: 삽수 · 씨앗 · 구근이면 REJECTED · PROHIBITED_FORM, 품종이 맞아도")
    void rejectProhibitedForm() {
        willReturn(new PlantIdentification(true, SaleForm.CUTTING, List.of(new Candidate(MONSTERA, null, 0.95))))
                .given(plantIdentifier).identify(anyList(), anyList());
        Product product = register("몬스테라 팝니다", null);

        imageReviewService.review(product.getId(), 0);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REJECTED);
        ImageReview review = reviewOf(product, 0);
        assertThat(review.getRejectReason()).isEqualTo(RejectReason.PROHIBITED_FORM);
        assertThat(review.getDetail()).isEqualTo("CUTTING");
        verify(outboxPublisher).publish(new ProductRejected(product.getId(), SELLER_ID, 0, "몬스테라 팝니다",
                "PROHIBITED_FORM", "씨앗 · 삽수 · 구근은 팔 수 없어요"));
    }

    // ── 사진 검수 결과가 관리자 확인: 상품은 검수 중 그대로, 사유 기록, 결과 이벤트 없음 ──

    private void assertManual(Product product, ManualReason reason, String detail) {
        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
        ImageReview review = reviewOf(product, 0);
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.DONE);
        assertThat(review.getDecision()).isEqualTo(ReviewDecision.MANUAL);
        assertThat(review.getManualReason()).isEqualTo(reason);
        assertThat(review.getDetail()).isEqualTo(detail);
        assertThat(review.getReviewedAt()).isNotNull();   // 관리자 확인 기한은 이때부터
        assertThat(review.getEngineResult()).isNotNull();   // 관리자 화면에 엔진 결과를 보여 줌
        verify(outboxPublisher, never()).publish(any(ProductStatusChanged.class));
        assertNoResultEvent();
    }

    @Test
    @DisplayName("관리자 확인: 1순위가 판매자 품종과 속이 다르면 SPECIES_MISMATCH (1순위 학명 기록)")
    void manualSpeciesMismatch() {
        identifyAs("Epipremnum aureum", 0.9);
        Product product = register("몬스테라 팝니다", null);

        imageReviewService.review(product.getId(), 0);

        assertManual(product, ManualReason.SPECIES_MISMATCH, "Epipremnum aureum");
    }

    @Test
    @DisplayName("관리자 확인: 1순위 확신도가 0.3 미만이면 LOW_CONFIDENCE")
    void manualLowConfidence() {
        identifyAs(MONSTERA, 0.2);
        Product product = register("몬스테라 팝니다", null);

        imageReviewService.review(product.getId(), 0);

        assertManual(product, ManualReason.LOW_CONFIDENCE, null);
    }

    @Test
    @DisplayName("관리자 확인: 후보가 없으면 LOW_CONFIDENCE")
    void manualNoCandidate() {
        willReturn(new PlantIdentification(true, SaleForm.PLANT, List.of()))
                .given(plantIdentifier).identify(anyList(), anyList());
        Product product = register("몬스테라 팝니다", null);

        imageReviewService.review(product.getId(), 0);

        assertManual(product, ManualReason.LOW_CONFIDENCE, null);
    }

    @Test
    @DisplayName("관리자 확인: 1순위 금지 품종(V8)이 0.7 미만이면 반려 대신 BANNED_SUSPECTED (금지 품종 이름 기록)")
    void manualBannedSuspected() {
        identifyAs("Phalaenopsis japonica", 0.5);
        Product product = register("난초 팝니다", null);

        imageReviewService.review(product.getId(), 0);

        assertManual(product, ManualReason.BANNED_SUSPECTED, "나도풍란");
    }

    @Test
    @DisplayName("관리자 확인: 2순위에 금지 품종이 있으면 1순위가 판매자 품종과 맞아도 BANNED_SUSPECTED")
    void manualBannedSecondCandidate() {
        willReturn(new PlantIdentification(true, SaleForm.PLANT, List.of(
                new Candidate(MONSTERA, null, 0.8), new Candidate("Phalaenopsis japonica", null, 0.15))))
                .given(plantIdentifier).identify(anyList(), anyList());
        Product product = register("몬스테라 팝니다", null);

        imageReviewService.review(product.getId(), 0);

        assertManual(product, ManualReason.BANNED_SUSPECTED, "나도풍란");
    }

    @Test
    @DisplayName("관리자 확인: 새 등록의 설명에 관리자 확인 단어(V8)가 있으면 품종이 맞아도 REVIEW_KEYWORD")
    void manualReviewKeyword() {
        identifyAs(MONSTERA, 0.95);
        Product product = register("몬스테라 팝니다", "자생지에서 캤어요");

        imageReviewService.review(product.getId(), 0);

        assertManual(product, ManualReason.REVIEW_KEYWORD, "자생지");
    }

    @Test
    @DisplayName("관리자 확인: 「기타」 품종(V11)이면 사진 속 식물이 무엇이든 OTHER_SPECIES, 상품은 검수 중 그대로")
    void manualOtherSpecies() {
        identifyAs(MONSTERA, 0.95);
        Species other = speciesRepository.findById(Species.OTHER_ID).orElseThrow();
        Product product = register(other, "희귀 식물 팝니다", null);

        imageReviewService.review(product.getId(), 0);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
        ImageReview review = reviewOf(product, 0);
        assertThat(review.getDecision()).isEqualTo(ReviewDecision.MANUAL);
        assertThat(review.getManualReason()).isEqualTo(ManualReason.OTHER_SPECIES);
        assertThat(review.getEngineResult()).contains(MONSTERA);   // 관리자가 엔진이 본 품종을 참고
        verify(outboxPublisher, never()).publish(any(ProductStatusChanged.class));
    }

    @Test
    @DisplayName("「기타」 품종이라도 금지 품종은 그대로 반려")
    void otherSpeciesStillRejectsBanned() {
        identifyAs("Phalaenopsis japonica", 0.9);
        Species other = speciesRepository.findById(Species.OTHER_ID).orElseThrow();
        Product product = register(other, "난초 팝니다", null);

        imageReviewService.review(product.getId(), 0);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REJECTED);
        assertThat(reviewOf(product, 0).getRejectReason()).isEqualTo(RejectReason.BANNED_SPECIES);
    }

    @Test
    @DisplayName("사진이 유해하면 식물 식별을 부르지 않고 반려")
    void harmfulSkipsHaiku() {
        given(moderationClient.isImageFlagged(any())).willReturn(true);
        Product product = register("몬스테라 팝니다", null);

        imageReviewService.review(product.getId(), 0);

        assertThat(reviewOf(product, 0).getRejectReason()).isEqualTo(RejectReason.HARMFUL_IMAGE);
        verify(plantIdentifier, never()).identify(anyList(), anyList());
    }

    @Test
    @DisplayName("API 키가 없으면 엔진 오류를 재처리하지 않고 바로 관리자 확인(ENGINE_ERROR), 상품은 검수 중 그대로")
    void engineErrorWithoutKeysGoesManual() {
        willThrow(new ReviewEngineException("timeout")).given(plantIdentifier).identify(anyList(), anyList());
        Product product = register("몬스테라 팝니다", null);

        imageReviewService.review(product.getId(), 0);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
        ImageReview review = reviewOf(product, 0);
        assertThat(review.getDecision()).isEqualTo(ReviewDecision.MANUAL);
        assertThat(review.getManualReason()).isEqualTo(ManualReason.ENGINE_ERROR);
        assertThat(review.getErrorMessage()).contains("timeout");
        verify(outboxPublisher, never()).publish(any(ProductStatusChanged.class));
        assertNoResultEvent();
    }

    @Test
    @DisplayName("검수 버전이 바뀐 뒤 온 옛 검수는 반영하지 않음 (STALE)")
    void staleVersion() {
        identifyAs(MONSTERA, 0.95);
        Product product = register("몬스테라 팝니다", null);
        tx.executeWithoutResult(status -> {
            Product locked = productRepository.findWithLockById(product.getId()).orElseThrow();
            ReviewScope scope = locked.update(species, "새 제목", null, new BigDecimal("10000"), null, null, null,
                    List.of("http://localhost:9000/bucket/products/7/b.jpg"));
            imageReviewService.request(locked, scope);
        });

        imageReviewService.review(product.getId(), 0);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
        assertThat(reviewOf(product, 0).getStatus()).isEqualTo(ReviewStatus.STALE);
        assertThat(reviewOf(product, 1).getStatus()).isEqualTo(ReviewStatus.REQUESTED);
        verify(plantIdentifier, never()).identify(anyList(), anyList());
        assertNoResultEvent();
    }

    @Test
    @DisplayName("같은 검수 요청이 두 번 와도 한 번만 검수")
    void duplicate() {
        identifyAs(MONSTERA, 0.95);
        Product product = register("몬스테라 팝니다", null);

        imageReviewService.review(product.getId(), 0);
        imageReviewService.review(product.getId(), 0);

        verify(plantIdentifier, times(1)).identify(anyList(), anyList());
        verify(outboxPublisher, times(1)).publish(any(ProductStatusChanged.class));
    }

    @Test
    @DisplayName("판매 중 상품의 글만 고쳤는데 관리자 확인 단어가 있으면 판매를 멈추고 검수 중(REVIEW_PENDING)")
    void textOnlyReviewWordStopsSelling() {
        Product product = register("몬스테라 팝니다", null);
        tx.executeWithoutResult(status ->   // 첫 검수를 통과해 판매 중
                productRepository.applyReviewResult(product.getId(), 0, ProductStatus.REVIEW_PENDING, ProductStatus.ACTIVE));
        tx.executeWithoutResult(status -> {
            Product locked = productRepository.findWithLockById(product.getId()).orElseThrow();
            ReviewScope scope = locked.update(species, "몬스테라 팝니다", "자생지에서 캤어요", new BigDecimal("10000"),
                    null, null, null, List.of("http://localhost:9000/bucket/products/7/a.jpg"));
            assertThat(scope).isEqualTo(ReviewScope.TEXT_ONLY);
            imageReviewService.request(locked, scope);
        });

        imageReviewService.review(product.getId(), 1);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
        ImageReview review = reviewOf(product, 1);
        assertThat(review.getManualReason()).isEqualTo(ManualReason.REVIEW_KEYWORD);
        // 판매 중 → 검수 중으로 상태만 바뀜, 결과 알림은 없음 (관리자 확인은 결과가 아님)
        verify(outboxPublisher).publish(any(ProductStatusChanged.class));
        assertNoResultEvent();
        verify(plantIdentifier, never()).identify(anyList(), anyList());
        verify(moderationClient, never()).isImageFlagged(any());
    }

    @Test
    @DisplayName("최근 7일 반려가 5건 이상이면 외부 API 없이 관리자 확인")
    void repeatedRejections() {
        for (int i = 0; i < ImageReviewService.RECENT_REJECTION_LIMIT; i++) {
            ImageReview rejected = ImageReview.requested(1000L + i, 0, SELLER_ID, ReviewScope.FULL);
            rejected.complete(ReviewVerdict.reject(RejectReason.NOT_PLANT, null), null, null, LocalDateTime.now().minusDays(1));
            imageReviewRepository.save(rejected);
        }
        Product product = register("몬스테라 팝니다", null);

        imageReviewService.review(product.getId(), 0);

        assertThat(reviewOf(product, 0).getManualReason()).isEqualTo(ManualReason.REPEATED_REJECTIONS);
        verify(moderationClient, never()).isImageFlagged(any());
        verify(plantIdentifier, never()).identify(anyList(), anyList());
    }

    // ── S-C10 엔진 오류 재처리 ───────────────────────

    private void keysConfigured() {
        given(moderationClient.isConfigured()).willReturn(true);
        given(plantIdentifier.isConfigured()).willReturn(true);
    }

    // 재처리 시각을 지난 것으로 (스케줄러 5분 간격을 기다리지 않게)
    private void makeRetryDue() {
        jdbc.update("update image_review set next_retry_at = now() - interval '1 minute' where status = 'ENGINE_ERROR'");
    }

    @Test
    @DisplayName("S-C10 엔진 오류: 상품은 검수 중 그대로, 검수 기록은 ENGINE_ERROR로 5분 뒤 재처리 대기")
    void engineErrorWaitsForRetry() {
        keysConfigured();
        willThrow(new ReviewEngineException("timeout")).given(plantIdentifier).identify(anyList(), anyList());
        Product product = register("몬스테라 팝니다", null);

        imageReviewService.review(product.getId(), 0);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
        ImageReview review = reviewOf(product, 0);
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.ENGINE_ERROR);
        assertThat(review.getDecision()).isNull();
        assertThat(review.getRetryCount()).isZero();
        assertThat(review.getNextRetryAt()).isAfter(LocalDateTime.now().plusMinutes(4));
        assertThat(review.getErrorMessage()).contains("timeout");
        verify(outboxPublisher, never()).publish(any(ProductStatusChanged.class));

        // 시각이 되기 전에는 재처리하지 않고, 같은 검수 요청이 다시 와도 검수하지 않음
        assertThat(imageReviewService.retryEngineErrors()).isZero();
        imageReviewService.review(product.getId(), 0);
        verify(plantIdentifier, times(1)).identify(anyList(), anyList());
    }

    @Test
    @DisplayName("S-C10 재처리가 성공하면 정상 판정 (통과 → ACTIVE), 하루 사진 검수 30회에 세지 않음")
    void retrySucceeds() {
        keysConfigured();
        willThrow(new ReviewEngineException("timeout")).given(plantIdentifier).identify(anyList(), anyList());
        Product product = register("몬스테라 팝니다", null);
        imageReviewService.review(product.getId(), 0);

        identifyAs(MONSTERA, 0.95);   // 엔진이 돌아옴
        makeRetryDue();
        assertThat(imageReviewService.retryEngineErrors()).isEqualTo(1);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.ACTIVE);
        ImageReview review = reviewOf(product, 0);
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.DONE);
        assertThat(review.getDecision()).isEqualTo(ReviewDecision.APPROVED);
        assertThat(review.getRetryCount()).isEqualTo(1);
        assertThat(review.getNextRetryAt()).isNull();
        verify(outboxPublisher).publish(any(ProductStatusChanged.class));
        // 검수 기록을 새로 만들지 않아 하루 사진 검수 횟수는 그대로 1
        assertThat(imageReviewRepository.countBySellerIdAndScopeAndCreatedAtGreaterThanEqual(
                SELLER_ID, ReviewScope.FULL, LocalDateTime.now().minusDays(1))).isEqualTo(1);
    }

    @Test
    @DisplayName("S-C10 재처리 3회 모두 실패하면 관리자 확인(ENGINE_ERROR), 자동 통과 · 반려 없음")
    void retryExhaustedGoesManual() {
        keysConfigured();
        willThrow(new ReviewEngineException("timeout")).given(plantIdentifier).identify(anyList(), anyList());
        Product product = register("몬스테라 팝니다", null);
        imageReviewService.review(product.getId(), 0);

        for (int i = 1; i <= ImageReviewService.MAX_ENGINE_RETRIES; i++) {
            makeRetryDue();
            assertThat(imageReviewService.retryEngineErrors()).isEqualTo(1);
        }

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
        ImageReview review = reviewOf(product, 0);
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.DONE);
        assertThat(review.getDecision()).isEqualTo(ReviewDecision.MANUAL);
        assertThat(review.getManualReason()).isEqualTo(ManualReason.ENGINE_ERROR);
        assertThat(review.getRetryCount()).isEqualTo(3);
        assertThat(review.getReviewedAt()).isNotNull();   // 관리자 확인 기한은 이때부터
        verify(plantIdentifier, times(4)).identify(anyList(), anyList());   // 처음 1번 + 재처리 3번

        makeRetryDue();
        assertThat(imageReviewService.retryEngineErrors()).isZero();   // 더는 재처리하지 않음
    }

    @Test
    @DisplayName("S-C10 그사이 상품이 수정돼 검수 버전이 다르면 재처리하지 않음 (STALE)")
    void retrySkipsChangedVersion() {
        keysConfigured();
        willThrow(new ReviewEngineException("timeout")).given(plantIdentifier).identify(anyList(), anyList());
        Product product = register("몬스테라 팝니다", null);
        imageReviewService.review(product.getId(), 0);
        tx.executeWithoutResult(status -> {
            Product locked = productRepository.findWithLockById(product.getId()).orElseThrow();
            ReviewScope scope = locked.update(species, "새 제목", null, new BigDecimal("10000"), null, null, null,
                    List.of("http://localhost:9000/bucket/products/7/b.jpg"));
            imageReviewService.request(locked, scope);
        });

        makeRetryDue();
        imageReviewService.retryEngineErrors();

        assertThat(reviewOf(product, 0).getStatus()).isEqualTo(ReviewStatus.STALE);
        assertThat(reviewOf(product, 1).getStatus()).isEqualTo(ReviewStatus.REQUESTED);
        verify(plantIdentifier, times(1)).identify(anyList(), anyList());
    }

    @Test
    @DisplayName("S-C10 글 검사 엔진 오류도 재처리: 판매 중 상품은 판매 중 그대로 기다림")
    void textOnlyEngineErrorKeepsActive() {
        keysConfigured();
        Product product = register("몬스테라 팝니다", null);
        tx.executeWithoutResult(status ->
                productRepository.applyReviewResult(product.getId(), 0, ProductStatus.REVIEW_PENDING, ProductStatus.ACTIVE));
        tx.executeWithoutResult(status -> {
            Product locked = productRepository.findWithLockById(product.getId()).orElseThrow();
            ReviewScope scope = locked.update(species, "몬스테라 팝니다", "설명을 고쳤어요", new BigDecimal("10000"),
                    null, null, null, List.of("http://localhost:9000/bucket/products/7/a.jpg"));
            imageReviewService.request(locked, scope);
        });
        willThrow(new ReviewEngineException("timeout")).given(moderationClient).isTextFlagged(anyString());

        imageReviewService.review(product.getId(), 1);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(reviewOf(product, 1).getStatus()).isEqualTo(ReviewStatus.ENGINE_ERROR);
    }

    @Test
    @DisplayName("#345 판매 중 상품의 글 검사에서 반려되면 Rejected(INAPPROPRIATE_TEXT), 통과면 상태가 그대로라 아무것도 보내지 않음")
    void textOnlyResultEvents() {
        Product product = register("몬스테라 팝니다", null);
        tx.executeWithoutResult(status ->
                productRepository.applyReviewResult(product.getId(), 0, ProductStatus.REVIEW_PENDING, ProductStatus.ACTIVE));
        updateText(product, "설명만 고쳤어요");
        imageReviewService.review(product.getId(), 1);
        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertNoResultEvent();
        verify(outboxPublisher, never()).publish(any(ProductStatusChanged.class));

        given(moderationClient.isTextFlagged(anyString())).willReturn(true);
        updateText(product, "유해한 설명");
        imageReviewService.review(product.getId(), 2);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REJECTED);
        verify(outboxPublisher).publish(new ProductRejected(product.getId(), SELLER_ID, 2, "몬스테라 팝니다",
                "INAPPROPRIATE_TEXT", "제목이나 설명에 부적절한 내용이 있어요"));
    }

    private void updateText(Product product, String description) {
        tx.executeWithoutResult(status -> {
            Product locked = productRepository.findWithLockById(product.getId()).orElseThrow();
            ReviewScope scope = locked.update(species, "몬스테라 팝니다", description, new BigDecimal("10000"),
                    null, null, null, List.of("http://localhost:9000/bucket/products/7/a.jpg"));
            imageReviewService.request(locked, scope);
        });
    }

    private void assertNoResultEvent() {
        verify(outboxPublisher, never()).publish(any(ProductApproved.class));
        verify(outboxPublisher, never()).publish(any(ProductRejected.class));
    }

    // ── 멈춘 검수 요청 (DB 오류로 DLT · 이벤트 유실) ───────────────────────

    @Test
    @DisplayName("30분이 지나도 REQUESTED인 검수는 재처리 스케줄러가 맡아 검수하고, 늦게 온 검수 요청은 건너뜀")
    void staleRequestIsRetried() {
        keysConfigured();
        identifyAs(MONSTERA, 0.95);
        Product product = register("몬스테라 팝니다", null);
        // 검수 요청 이벤트가 리스너에서 처리되지 못한 채 31분이 지남
        jdbc.update("update image_review set created_at = now() - interval '31 minutes' where status = 'REQUESTED'");

        assertThat(imageReviewService.retryEngineErrors()).isEqualTo(1);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.ACTIVE);
        ImageReview review = reviewOf(product, 0);
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.DONE);
        assertThat(review.getRetryCount()).isEqualTo(1);   // 재처리 3회 안에서 셈

        // DLT 재처리 등으로 같은 검수 요청이 늦게 와도 다시 검수하지 않음
        imageReviewService.review(product.getId(), 0);
        verify(plantIdentifier, times(1)).identify(anyList(), anyList());
    }

    @Test
    @DisplayName("만든 지 30분이 안 된 REQUESTED는 리스너 몫이라 재처리 스케줄러가 집지 않음")
    void freshRequestIsLeftToListener() {
        keysConfigured();
        Product product = register("몬스테라 팝니다", null);
        jdbc.update("update image_review set created_at = now() - interval '29 minutes' where status = 'REQUESTED'");

        assertThat(imageReviewService.retryEngineErrors()).isZero();
        assertThat(reviewOf(product, 0).getStatus()).isEqualTo(ReviewStatus.REQUESTED);
        verify(plantIdentifier, never()).identify(anyList(), anyList());
    }
}
