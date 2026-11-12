package com.justcommit.backend.product.product.application;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.ProductUseCase;
import com.justcommit.backend.product.client.member.MemberQuery;
import com.justcommit.backend.product.config.ClockConfig;
import com.justcommit.backend.product.image.application.ProductImageCleaner;
import com.justcommit.backend.product.image.application.ProductImageUrlValidator;
import com.justcommit.backend.product.member.application.MemberReplicaService;
import com.justcommit.backend.product.member.infrastructure.MemberReplicaRepository;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.reservation.infrastructure.ProductReservationRepository;
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
import org.springframework.context.annotation.Import;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 판매자 탈퇴 뒤에 해제되는 상품도 DELETED (docs/services/product.md 「판매자 탈퇴」)
// 탈퇴와 해제가 동시에 실행돼도 맞는지는 행 잠금 동작이라 운영과 같은 PostgreSQL로 확인
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class, ProductService.class, MemberReplicaService.class})
@Testcontainers(disabledWithoutDocker = true)
// 테스트 메서드를 트랜잭션으로 감싸지 않음 → 준비 데이터가 커밋되어 다른 스레드에서도 보임
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SellerWithdrawalReleaseIntegrationTest {

    private static final Long SELLER_ID = 7L;
    private static final Long ORDER_ID = 1L;
    private static final BigDecimal PRICE = new BigDecimal("10000");
    private static final OffsetDateTime WITHDRAWN_AT = OffsetDateTime.of(2026, 11, 11, 10, 0, 0, 0, ZoneOffset.ofHours(9));
    // 다른 트랜잭션이 잠금을 쥔 동안 이 시간 안에 끝나면 기다리지 않은 것
    private static final long BLOCKED_CHECK_MILLIS = 500;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired ProductUseCase productUseCase;
    @Autowired MemberReplicaService memberReplicaService;
    @Autowired ProductRepository productRepository;
    @Autowired SpeciesRepository speciesRepository;
    @Autowired ProductReservationRepository reservationRepository;
    @Autowired MemberReplicaRepository memberReplicaRepository;
    @Autowired PlatformTransactionManager transactionManager;

    // 예약 · 해제 흐름에서 쓰지 않는 ProductService 의존성은 목으로 대체
    @MockitoBean MemberQuery memberQuery;
    // @DataJpaTest라 Kafka가 없음: 발행은 가짜로 (Outbox 저장 · 롤백은 따로 테스트)
    @MockitoBean  OutboxPublisher outboxPublisher;
    @MockitoBean ProductImageUrlValidator imageUrlValidator;
    @MockitoBean ProductImageCleaner imageCleaner;

    private Species species;

    @BeforeEach
    void setUp() {
        species = BeanUtils.instantiateClass(Species.class);
        ReflectionTestUtils.setField(species, "name", "탈퇴 해제 테스트 품종"); // V4가 넣은 품종과 이름이 겹치지 않게
        speciesRepository.save(species);
    }

    @AfterEach
    void cleanUp() {
        productRepository.deleteAllInBatch();
        speciesRepository.deleteById(species.getId());
        reservationRepository.deleteAllInBatch();
        memberReplicaRepository.deleteAllInBatch();
    }

    // ── 탈퇴가 끝난 뒤에 해제 ───────────────────────

    @Test
    @DisplayName("예약 → 판매자 탈퇴 → 주문 만료면 상품은 판매 중이 아니라 DELETED가 된다")
    void releaseAfterWithdrawalDeletes() {
        Long productId = saveReservedProduct();
        memberReplicaService.applyWithdrawn(SELLER_ID, WITHDRAWN_AT);
        assertStatus(productId, ProductStatus.PAYMENT_PENDING); // 탈퇴 때는 주문 기록 때문에 남김

        assertThat(productUseCase.release(ORDER_ID)).isTrue();

        assertStatus(productId, ProductStatus.DELETED);
        assertThat(productRepository.findById(productId).orElseThrow().getReservedOrderId()).isNull();
    }

    @Test
    @DisplayName("판매 완료 → 판매자 탈퇴 → 취소 · 반품이면 판매 재개 대신 DELETED가 된다")
    void releaseItemAfterWithdrawalDeletes() {
        Long productId = saveReservedProduct();
        assertThat(productUseCase.markSoldOut(ORDER_ID)).isTrue();
        memberReplicaService.applyWithdrawn(SELLER_ID, WITHDRAWN_AT);
        assertStatus(productId, ProductStatus.SOLD_OUT);

        assertThat(productUseCase.releaseItem(productId, ORDER_ID, false)).isTrue();

        assertStatus(productId, ProductStatus.DELETED);
    }

    @Test
    @DisplayName("다른 판매자가 탈퇴해도 내 상품은 해제되면 판매 중으로 돌아온다")
    void otherSellerWithdrawalDoesNotAffect() {
        Long productId = saveReservedProduct();
        memberReplicaService.applyWithdrawn(SELLER_ID + 1, WITHDRAWN_AT);

        assertThat(productUseCase.release(ORDER_ID)).isTrue();

        assertStatus(productId, ProductStatus.ACTIVE);
    }

    // ── 탈퇴와 해제가 동시에 ───────────────────────
    // 한쪽 트랜잭션을 커밋 직전에 멈춰 두고 다른 쪽을 실행해, 어느 쪽이 먼저 잠가도 결과가 DELETED인지 확인

    @Test
    @DisplayName("탈퇴가 커밋 전일 때 해제가 오면, 해제는 탈퇴 커밋을 기다렸다가 DELETED로 만든다")
    void releaseWaitsForUncommittedWithdrawal() throws Exception {
        Long productId = saveReservedProduct();

        runWhileHolding(
                () -> memberReplicaService.applyWithdrawn(SELLER_ID, WITHDRAWN_AT),
                () -> productUseCase.release(ORDER_ID));

        assertStatus(productId, ProductStatus.DELETED);
    }

    @Test
    @DisplayName("해제가 커밋 전일 때 탈퇴가 오면, 탈퇴는 해제 커밋을 기다렸다가 판매 중이 된 상품을 DELETED로 만든다")
    void withdrawalWaitsForUncommittedRelease() throws Exception {
        Long productId = saveReservedProduct();

        runWhileHolding(
                () -> productUseCase.release(ORDER_ID),
                () -> memberReplicaService.applyWithdrawn(SELLER_ID, WITHDRAWN_AT));

        assertStatus(productId, ProductStatus.DELETED);
    }

    @Test
    @DisplayName("판매 재개가 커밋 전일 때 탈퇴가 와도 상품은 DELETED가 된다")
    void withdrawalWaitsForUncommittedReleaseItem() throws Exception {
        Long productId = saveReservedProduct();
        assertThat(productUseCase.markSoldOut(ORDER_ID)).isTrue();

        runWhileHolding(
                () -> productUseCase.releaseItem(productId, ORDER_ID, false),
                () -> memberReplicaService.applyWithdrawn(SELLER_ID, WITHDRAWN_AT));

        assertStatus(productId, ProductStatus.DELETED);
    }

    // first를 트랜잭션 안에서 실행하고 커밋하지 않은 채 멈춘다 → second를 실행해 first의 잠금을 기다리는지 확인
    // → first를 커밋시키고 둘 다 끝날 때까지 기다린다
    private void runWhileHolding(Runnable first, Runnable second) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstFuture = executor.submit(() -> tx.executeWithoutResult(status -> {
                first.run();
                firstDone.countDown();
                await(commitFirst);
            }));
            assertThat(firstDone.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> secondFuture = executor.submit(second);
            // first가 상품 행을 잠그고 있으므로 second는 끝나지 못하고 기다려야 함
            assertThatThrownBy(() -> secondFuture.get(BLOCKED_CHECK_MILLIS, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            commitFirst.countDown();
            firstFuture.get(10, TimeUnit.SECONDS);
            secondFuture.get(10, TimeUnit.SECONDS);
        } finally {
            commitFirst.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("커밋 신호를 기다리다 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // 판매자 7의 상품을 주문 1로 예약해 결제 대기 상태로 둔다 (예약은 판매 중 상품만 가능)
    private Long saveReservedProduct() {
        Product product = Product.create(species, SELLER_ID, "몬스테라 중품", "설명", PRICE,
                null, null, null, List.of());
        ReflectionTestUtils.setField(product, "status", ProductStatus.ACTIVE);
        Long productId = productRepository.save(product).getId();
        productUseCase.reserve(ORDER_ID, Map.of(productId, PRICE));
        assertStatus(productId, ProductStatus.PAYMENT_PENDING);
        return productId;
    }

    private void assertStatus(Long productId, ProductStatus expected) {
        assertThat(productRepository.findById(productId).orElseThrow().getStatus()).isEqualTo(expected);
    }
}
