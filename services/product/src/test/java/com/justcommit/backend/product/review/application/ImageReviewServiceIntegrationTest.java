package com.justcommit.backend.product.review.application;

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
@Import({JpaAuditingConfig.class, ClockConfig.class, ImageReviewService.class, ReviewJudgeProvider.class,
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
        verify(outboxPublisher).publish(new ProductStatusChanged(product.getId()));
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
        verify(outboxPublisher).publish(new ProductStatusChanged(product.getId()));
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
}
