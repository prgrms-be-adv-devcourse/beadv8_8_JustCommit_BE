package com.justcommit.backend.product.review.application;

import com.justcommit.backend.product.product.application.ProductEventPublisher;
import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.config.ClockConfig;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.domain.ReviewScope;
import com.justcommit.backend.product.product.event.ProductApproved;
import com.justcommit.backend.product.product.event.ProductRejected;
import com.justcommit.backend.product.product.event.ProductStatusChanged;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.review.domain.ImageReview;
import com.justcommit.backend.product.review.domain.ReviewVerdict;
import com.justcommit.backend.product.review.domain.enums.DecidedBy;
import com.justcommit.backend.product.review.domain.enums.ManualReason;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import com.justcommit.backend.product.review.domain.enums.ReviewDecision;
import com.justcommit.backend.product.review.domain.enums.ReviewStatus;
import com.justcommit.backend.product.review.infrastructure.ImageReviewRepository;
import com.justcommit.backend.product.review.presentation.dto.AdminImageReviewResponse;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 관리자 검수 확인 · 24시간 기한 자동 반려를 실제 PostgreSQL(V12)로 확인. Outbox 발행만 가짜
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class, AdminImageReviewService.class, ReviewResultPublisher.class, ProductEventPublisher.class,
        AdminImageReviewServiceIntegrationTest.Config.class})
@Testcontainers(disabledWithoutDocker = true)
// 서비스가 트랜잭션을 나눠 쓰므로 테스트 메서드는 트랜잭션으로 감싸지 않음
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminImageReviewServiceIntegrationTest {

    private static final Long SELLER_ID = 7L;
    private static final Long ADMIN_ID = 1L;
    private static final Long OTHER_ADMIN_ID = 2L;

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

    @Autowired AdminImageReviewService adminService;
    @Autowired ImageReviewRepository imageReviewRepository;
    @Autowired ProductRepository productRepository;
    @Autowired SpeciesRepository speciesRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean OutboxPublisher outboxPublisher;

    private Species species;

    @BeforeEach
    void setUp() {
        species = BeanUtils.instantiateClass(Species.class);
        ReflectionTestUtils.setField(species, "name", "관리자 검수 테스트 품종");
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

    // 검수 중 상품 + 엔진이 hoursAgo 시간 전에 관리자 확인(품종 불일치)으로 보낸 검수 기록
    private Product awaitingAdmin(String title, long hoursAgo) {
        return tx.execute(status -> {
            Product product = productRepository.save(Product.create(species, SELLER_ID, title, null,
                    new BigDecimal("10000"), null, null, null, List.of("http://localhost:9000/bucket/products/7/a.jpg")));
            ImageReview review = ImageReview.requested(product.getId(), product.getReviewVersion(), SELLER_ID, ReviewScope.FULL);
            review.complete(ReviewVerdict.manual(ManualReason.SPECIES_MISMATCH, "Epipremnum aureum"),
                    "{\"moderation\":{\"imageFlagged\":false},\"plant\":{\"plant\":true}}", null,
                    LocalDateTime.now().minusHours(hoursAgo));
            imageReviewRepository.save(review);
            return product;
        });
    }

    private ImageReview reviewOf(Product product) {
        return imageReviewRepository.findByProductIdAndReviewVersion(product.getId(), 0).orElseThrow();
    }

    private Product reload(Product product) {
        return productRepository.findById(product.getId()).orElseThrow();
    }

    // payload v2 (#456): 반영 직후 DB 값(새 상태 · 새 상품 버전)이 그대로 담겨야 함
    private ProductStatusChanged statusChangedOf(Product product) {
        Product reloaded = reload(product);
        return new ProductStatusChanged(reloaded.getId(), reloaded.getSpecies().getId(), reloaded.getSellerId(),
                reloaded.getStatus().name(), reloaded.isHidden(), reloaded.getVersion());
    }

    // 판매자가 수정해 검수 버전이 올라감
    private void editByseller(Product product) {
        tx.executeWithoutResult(status -> {
            Product locked = productRepository.findWithLockById(product.getId()).orElseThrow();
            locked.update(species, "새 제목", null, new BigDecimal("10000"), null, null, null,
                    List.of("http://localhost:9000/bucket/products/7/b.jpg"));
        });
    }

    // ── 목록 ───────────────────────────────

    @Test
    @DisplayName("목록: 관리자 확인 대기만 오래된 순, 12시간 지나면 지연 표시, 기한은 24시간 뒤")
    void listOldestFirst() {
        Product fresh = awaitingAdmin("새 건", 1);
        Product old = awaitingAdmin("오래된 건", 13);

        List<AdminImageReviewResponse> list = adminService.findAwaiting(PageRequest.of(0, 20)).getContent();

        assertThat(list).extracting(AdminImageReviewResponse::productId).containsExactly(old.getId(), fresh.getId());
        AdminImageReviewResponse first = list.get(0);
        assertThat(first.delayed()).isTrue();
        assertThat(list.get(1).delayed()).isFalse();
        assertThat(first.expiresAt()).isEqualTo(reviewOf(old).getReviewedAt().plusHours(24));
        assertThat(first.manualReason()).isEqualTo("SPECIES_MISMATCH");
        assertThat(first.detail()).isEqualTo("Epipremnum aureum");
        assertThat(first.speciesScientificName()).isEqualTo("Monstera deliciosa");
        assertThat(first.otherSpecies()).isFalse();
        assertThat(first.imageUrls()).containsExactly("http://localhost:9000/bucket/products/7/a.jpg");
        assertThat(first.engineResult().get("plant").get("plant").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("목록: 그사이 판매자가 수정 · 삭제한 상품, 이미 처리한 검수는 빠짐")
    void listExcludesChanged() {
        Product edited = awaitingAdmin("수정됨", 2);
        Product deleted = awaitingAdmin("삭제됨", 2);
        Product decided = awaitingAdmin("처리됨", 2);
        Product waiting = awaitingAdmin("대기", 2);
        editByseller(edited);
        tx.executeWithoutResult(status -> productRepository.findById(deleted.getId()).orElseThrow().delete());
        adminService.decide(reviewOf(decided).getId(), ADMIN_ID, ReviewDecision.APPROVED, null, null);

        assertThat(adminService.findAwaiting(PageRequest.of(0, 20)).getContent())
                .extracting(AdminImageReviewResponse::productId).containsExactly(waiting.getId());
    }

    // ── 처리 ───────────────────────────────

    @Test
    @DisplayName("통과: ACTIVE, 처리자 · 결과 · 시각을 검수 기록에 남기고 통과 알림 이벤트")
    void approve() {
        Product product = awaitingAdmin("몬스테라 팝니다", 1);

        adminService.decide(reviewOf(product).getId(), ADMIN_ID, ReviewDecision.APPROVED, RejectReason.NOT_PLANT, "무시됨");

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.ACTIVE);
        ImageReview review = reviewOf(product);
        assertThat(review.getDecision()).isEqualTo(ReviewDecision.APPROVED);
        assertThat(review.getRejectReason()).isNull();
        assertThat(review.getAdminMemo()).isNull();
        assertThat(review.getDecidedBy()).isEqualTo(DecidedBy.ADMIN);
        assertThat(review.getAdminId()).isEqualTo(ADMIN_ID);
        assertThat(review.getDecidedAt()).isNotNull();
        assertThat(review.getManualReason()).isEqualTo(ManualReason.SPECIES_MISMATCH);   // 관리자 확인으로 간 이유는 그대로
        verify(outboxPublisher).publish(statusChangedOf(product));
        verify(outboxPublisher).publish(new ProductApproved(product.getId(), SELLER_ID, 0, "몬스테라 팝니다"));
    }

    @Test
    @DisplayName("반려: REJECTED, 사유 코드와 판매자 문구로 반려 알림 이벤트")
    void reject() {
        Product product = awaitingAdmin("몬스테라 팝니다", 1);

        adminService.decide(reviewOf(product).getId(), ADMIN_ID, ReviewDecision.REJECTED, RejectReason.SPECIES_MISMATCH, null);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REJECTED);
        assertThat(reviewOf(product).getRejectReason()).isEqualTo(RejectReason.SPECIES_MISMATCH);
        verify(outboxPublisher).publish(new ProductRejected(product.getId(), SELLER_ID, 0, "몬스테라 팝니다",
                "SPECIES_MISMATCH", "선택한 품종과 사진 속 식물이 달라요"));
    }

    @Test
    @DisplayName("반려 OTHER: 관리자가 입력한 내용을 기록하고 판매자 문구로 보냄")
    void rejectOther() {
        Product product = awaitingAdmin("몬스테라 팝니다", 1);

        adminService.decide(reviewOf(product).getId(), ADMIN_ID, ReviewDecision.REJECTED, RejectReason.OTHER, "사진에 가격표가 보여요");

        assertThat(reviewOf(product).getAdminMemo()).isEqualTo("사진에 가격표가 보여요");
        verify(outboxPublisher).publish(new ProductRejected(product.getId(), SELLER_ID, 0, "몬스테라 팝니다",
                "OTHER", "사진에 가격표가 보여요"));
    }

    @Test
    @DisplayName("400: 반려 사유 없음 · REVIEW_EXPIRED · OTHER인데 내용 없음 · 200자 넘음 · MANUAL, 상품 · 기록은 그대로")
    void invalidReason() {
        Product product = awaitingAdmin("몬스테라 팝니다", 1);
        Long reviewId = reviewOf(product).getId();

        assertInvalid(reviewId, ReviewDecision.REJECTED, null, null);
        assertInvalid(reviewId, ReviewDecision.REJECTED, RejectReason.REVIEW_EXPIRED, null);
        assertInvalid(reviewId, ReviewDecision.REJECTED, RejectReason.OTHER, " ");
        assertInvalid(reviewId, ReviewDecision.REJECTED, RejectReason.OTHER, "가".repeat(201));
        assertInvalid(reviewId, ReviewDecision.MANUAL, null, null);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
        assertThat(reviewOf(product).isAwaitingAdmin()).isTrue();
        verify(outboxPublisher, never()).publish(any());
    }

    private void assertInvalid(Long reviewId, ReviewDecision decision, RejectReason reason, String memo) {
        assertThatThrownBy(() -> adminService.decide(reviewId, ADMIN_ID, decision, reason, memo))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.INVALID_REJECT_REASON);
    }

    @Test
    @DisplayName("409 REVIEW_ALREADY_PROCESSED: 이미 처리한 검수를 다시 처리, 없는 검수는 404")
    void alreadyProcessed() {
        Product product = awaitingAdmin("몬스테라 팝니다", 1);
        Long reviewId = reviewOf(product).getId();
        adminService.decide(reviewId, ADMIN_ID, ReviewDecision.APPROVED, null, null);

        assertThatThrownBy(() -> adminService.decide(reviewId, OTHER_ADMIN_ID, ReviewDecision.REJECTED, RejectReason.NOT_PLANT, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.REVIEW_ALREADY_PROCESSED);
        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(reviewOf(product).getAdminId()).isEqualTo(ADMIN_ID);
        assertThatThrownBy(() -> adminService.decide(-1L, ADMIN_ID, ReviewDecision.APPROVED, null, null))
                .extracting("errorCode").isEqualTo(ProductErrorCode.REVIEW_NOT_FOUND);
    }

    @Test
    @DisplayName("409 REVIEW_OUTDATED: 관리자가 보는 동안 판매자가 수정 · 삭제")
    void outdated() {
        Product edited = awaitingAdmin("수정됨", 1);
        Product deleted = awaitingAdmin("삭제됨", 1);
        editByseller(edited);
        tx.executeWithoutResult(status -> productRepository.findById(deleted.getId()).orElseThrow().delete());

        for (Product product : List.of(edited, deleted)) {
            assertThatThrownBy(() -> adminService.decide(reviewOf(product).getId(), ADMIN_ID, ReviewDecision.APPROVED, null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ProductErrorCode.REVIEW_OUTDATED);
        }
        assertThat(reload(edited).getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
        assertThat(reload(deleted).getStatus()).isEqualTo(ProductStatus.DELETED);
        verify(outboxPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("두 관리자가 동시에 처리하면 한 명만 반영, 다른 쪽은 409")
    void concurrentDecisions() throws Exception {
        Product product = awaitingAdmin("몬스테라 팝니다", 1);
        Long reviewId = reviewOf(product).getId();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Callable<ProductErrorCode>> tasks = List.of(
                () -> decideAfter(start, reviewId, ADMIN_ID, ReviewDecision.APPROVED, null),
                () -> decideAfter(start, reviewId, OTHER_ADMIN_ID, ReviewDecision.REJECTED, RejectReason.NOT_PLANT));
        List<Future<ProductErrorCode>> futures = new ArrayList<>();
        for (Callable<ProductErrorCode> task : tasks) {
            futures.add(executor.submit(task));
        }
        start.countDown();
        List<ProductErrorCode> results = new ArrayList<>();
        for (Future<ProductErrorCode> future : futures) {
            results.add(future.get());
        }
        executor.shutdown();

        assertThat(results).containsExactlyInAnyOrder(null, ProductErrorCode.REVIEW_ALREADY_PROCESSED);
        verify(outboxPublisher, times(1)).publish(any(ProductStatusChanged.class));
    }

    // 성공이면 null, 실패면 오류 코드
    private ProductErrorCode decideAfter(CountDownLatch start, Long reviewId, Long adminId, ReviewDecision decision, RejectReason reason)
            throws InterruptedException {
        start.await();
        try {
            adminService.decide(reviewId, adminId, decision, reason, null);
            return null;
        } catch (BusinessException e) {
            return (ProductErrorCode) e.getErrorCode();
        }
    }

    @Test
    @DisplayName("판매 중 상품의 글 검사가 관리자 확인으로 간 건도 통과하면 다시 ACTIVE")
    void approveTextOnly() {
        Product product = tx.execute(status -> {
            Product saved = productRepository.save(Product.create(species, SELLER_ID, "몬스테라 팝니다", "자생지에서 캤어요",
                    new BigDecimal("10000"), null, null, null, List.of("http://localhost:9000/bucket/products/7/a.jpg")));
            ImageReview review = ImageReview.requested(saved.getId(), 0, SELLER_ID, ReviewScope.TEXT_ONLY);
            review.complete(ReviewVerdict.manual(ManualReason.REVIEW_KEYWORD, "자생지"), null, null, LocalDateTime.now());
            imageReviewRepository.save(review);
            return saved;
        });

        adminService.decide(reviewOf(product).getId(), ADMIN_ID, ReviewDecision.APPROVED, null, null);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    // ── 24시간 기한 ───────────────────────────────

    @Test
    @DisplayName("S-C07 24시간이 지난 관리자 확인은 REVIEW_EXPIRED 자동 반려 · 반려 알림 이벤트, 24시간 전은 그대로")
    void expireOverdue() {
        Product overdue = awaitingAdmin("기한 지남", 25);
        Product notYet = awaitingAdmin("아직", 23);

        assertThat(adminService.expireOverdue()).isEqualTo(1);

        assertThat(reload(overdue).getStatus()).isEqualTo(ProductStatus.REJECTED);
        ImageReview review = reviewOf(overdue);
        assertThat(review.getDecision()).isEqualTo(ReviewDecision.REJECTED);
        assertThat(review.getRejectReason()).isEqualTo(RejectReason.REVIEW_EXPIRED);
        assertThat(review.getDecidedBy()).isEqualTo(DecidedBy.DEADLINE);
        assertThat(review.getAdminId()).isNull();
        verify(outboxPublisher).publish(new ProductRejected(overdue.getId(), SELLER_ID, 0, "기한 지남",
                "REVIEW_EXPIRED", "확인이 늦어져 반려됐어요. 다시 등록할 수 있어요"));
        assertThat(reload(notYet).getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
        assertThat(reviewOf(notYet).isAwaitingAdmin()).isTrue();

        // 이미 반려한 건은 다시 집지 않음
        assertThat(adminService.expireOverdue()).isZero();
    }

    @Test
    @DisplayName("S-C07 REVIEW_EXPIRED는 최근 7일 반려 수에 세지 않음")
    void expiredNotCountedAsRejection() {
        awaitingAdmin("기한 지남", 25);
        adminService.expireOverdue();

        assertThat(imageReviewRepository.countBySellerIdAndDecisionAndRejectReasonNotAndReviewedAtGreaterThanEqual(
                SELLER_ID, ReviewDecision.REJECTED, RejectReason.REVIEW_EXPIRED, LocalDateTime.now().minusDays(7))).isZero();
    }

    @Test
    @DisplayName("기한이 지났는데 그사이 판매자가 수정한 옛 검수는 반려하지 않고 STALE로 정리")
    void expireSkipsChangedProduct() {
        Product product = awaitingAdmin("수정됨", 25);
        editByseller(product);

        assertThat(adminService.expireOverdue()).isEqualTo(1);

        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.REVIEW_PENDING);
        assertThat(reviewOf(product).getStatus()).isEqualTo(ReviewStatus.STALE);
        verify(outboxPublisher, never()).publish(any());
        assertThat(adminService.expireOverdue()).isZero();
    }

    @Test
    @DisplayName("관리자가 이미 처리한 건은 24시간이 지나도 자동 반려하지 않음")
    void expireAfterAdminDecision() {
        Product product = awaitingAdmin("기한 지남", 25);
        adminService.decide(reviewOf(product).getId(), ADMIN_ID, ReviewDecision.APPROVED, null, null);

        assertThat(adminService.expireOverdue()).isZero();
        assertThat(reload(product).getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }
}
