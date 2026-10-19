package com.justcommit.backend.product.product.application;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.member.MemberQuery;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.ProductUseCase;
import com.justcommit.backend.product.image.application.ProductImageCleaner;
import com.justcommit.backend.product.image.application.ProductImageUrlValidator;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ProductService.class})
@Testcontainers(disabledWithoutDocker = true)
// 테스트 메서드를 트랜잭션으로 감싸지 않음 → 준비 데이터가 실제로 커밋되어 다른 스레드에서도 보임
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductReserveConcurrencyIntegrationTest {

    private static final int THREADS = 10;

    // 행 잠금 동작은 DB마다 달라서 운영과 같은 PostgreSQL로 검증
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired ProductUseCase productUseCase;
    @Autowired ProductRepository productRepository;
    @Autowired SpeciesRepository speciesRepository;

    // 예약 흐름에서 쓰지 않는 ProductService 의존성은 목으로 대체
    @MockitoBean MemberQuery memberQuery;
    @MockitoBean ProductImageUrlValidator imageUrlValidator;
    @MockitoBean ProductImageCleaner imageCleaner;

    private Species species;

    @BeforeEach
    void setUp() {
        species = BeanUtils.instantiateClass(Species.class);
        ReflectionTestUtils.setField(species, "name", "몬스테라");
        speciesRepository.save(species);
    }

    @AfterEach
    void cleanUp() {
        productRepository.deleteAllInBatch();
        speciesRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("여러 주문이 같은 상품을 동시에 예약하면 한 주문만 성공하고, 상품은 그 주문으로 예약된다")
    void onlyOneOrderReservesSameProductConcurrently() throws Exception {
        Long productId = saveActiveProduct();

        // 주문 100 ~ 109가 같은 상품을 동시에 예약
        List<Boolean> results = runConcurrently(
                i -> () -> productUseCase.reserve(100L + i, List.of(productId)));

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(results).filteredOn(reserved -> !reserved).hasSize(THREADS - 1);

        // 성공한 주문이 상품의 예약 주인이어야 함
        Long winnerOrderId = 100L + IntStream.range(0, THREADS)
                .filter(results::get)
                .findFirst()
                .orElseThrow();
        Product product = productRepository.findById(productId).orElseThrow();
        assertThat(product.getStatus()).isEqualTo(ProductStatus.PAYMENT_PENDING);
        assertThat(product.getReservedOrderId()).isEqualTo(winnerOrderId);
    }

    @Test
    @DisplayName("여러 상품 중 하나라도 이미 예약돼 있으면 아무것도 예약하지 않는다")
    void reservesNothingWhenAnyProductIsAlreadyReserved() {
        Long taken = saveActiveProduct();
        Long free1 = saveActiveProduct();
        Long free2 = saveActiveProduct();
        assertThat(productUseCase.reserve(1L, List.of(taken))).isTrue();

        boolean reserved = productUseCase.reserve(2L, List.of(free1, taken, free2));

        assertThat(reserved).isFalse();
        // 주문 2가 잠깐 잡았던 상품은 다시 판매 중으로 돌아와야 함
        assertActive(free1);
        assertActive(free2);
        // 이미 예약된 상품은 원래 주문 그대로
        assertReservedBy(taken, 1L);
    }

    @Test
    @DisplayName("상품이 겹치는 주문들이 동시에 예약해도, 진 주문이 잡았던 상품은 모두 해제된다")
    void releasesPartiallyReservedProductsOfLosingOrdersConcurrently() throws Exception {
        // 모든 주문이 같은 인기 상품 1개 + 각자 다른 상품 1개를 함께 예약
        Long shared = saveActiveProduct();
        List<Long> own = IntStream.range(0, THREADS).mapToObj(i -> saveActiveProduct()).toList();

        List<Boolean> results = runConcurrently(
                i -> () -> productUseCase.reserve(100L + i, List.of(shared, own.get(i))));

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);

        for (int i = 0; i < THREADS; i++) {
            Long orderId = 100L + i;
            if (results.get(i)) {
                // 이긴 주문은 두 상품 모두 자기 것
                assertReservedBy(shared, orderId);
                assertReservedBy(own.get(i), orderId);
            } else {
                // 진 주문이 자기 상품만 잡고 남아 있으면 안 됨
                assertActive(own.get(i));
            }
        }
    }

    @Test
    @DisplayName("같은 주문에 결제 완료와 만료가 동시에 실행되면 둘 중 하나만 반영된다")
    void onlyOneOfSoldOutAndReleaseAppliesConcurrently() throws Exception {
        Long productId = saveActiveProduct();
        Long orderId = 1L;
        assertThat(productUseCase.reserve(orderId, List.of(productId))).isTrue();

        // 짝수 스레드는 결제 완료, 홀수 스레드는 주문 만료를 같은 주문에 동시 실행
        List<Boolean> results = runConcurrently(i -> i % 2 == 0
                ? () -> productUseCase.markSoldOut(orderId)
                : () -> productUseCase.release(orderId));

        // 둘 다 결제 대기 상태일 때만 바꾸므로, 먼저 실행된 한 건만 성공
        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);

        int winner = results.indexOf(true);
        Product product = productRepository.findById(productId).orElseThrow();
        if (winner % 2 == 0) {
            // 결제 완료가 이기면 판매 완료, 예약 주문은 그대로
            assertThat(product.getStatus()).isEqualTo(ProductStatus.SOLD_OUT);
            assertThat(product.getReservedOrderId()).isEqualTo(orderId);
        } else {
            // 만료가 이기면 판매 중으로 복원
            assertActive(productId);
        }
    }

    @Test
    @DisplayName("만료 후 다른 주문이 재예약한 상품에 이전 주문의 결제 완료·만료가 늦게 와도 새 예약은 유지된다")
    void ignoresLateRequestsOfExpiredOrderAfterReReservation() {
        Long productId = saveActiveProduct();
        Long expiredOrderId = 10L;
        Long newOrderId = 11L;

        // 주문 10 예약 → 만료로 해제 → 주문 11이 같은 상품을 다시 예약
        assertThat(productUseCase.reserve(expiredOrderId, List.of(productId))).isTrue();
        assertThat(productUseCase.release(expiredOrderId)).isTrue();
        assertThat(productUseCase.reserve(newOrderId, List.of(productId))).isTrue();

        // 주문 10의 결제 완료·만료 요청이 뒤늦게 도착 → 예약 주인이 아니므로 0건 처리
        assertThat(productUseCase.markSoldOut(expiredOrderId)).isFalse();
        assertThat(productUseCase.release(expiredOrderId)).isFalse();
        assertReservedBy(productId, newOrderId);

        // 새 주문의 결제 완료는 정상 반영
        assertThat(productUseCase.markSoldOut(newOrderId)).isTrue();
        Product product = productRepository.findById(productId).orElseThrow();
        assertThat(product.getStatus()).isEqualTo(ProductStatus.SOLD_OUT);
        assertThat(product.getReservedOrderId()).isEqualTo(newOrderId);
    }

    private void assertActive(Long productId) {
        Product product = productRepository.findById(productId).orElseThrow();
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(product.getReservedOrderId()).isNull();
    }

    private void assertReservedBy(Long productId, Long orderId) {
        Product product = productRepository.findById(productId).orElseThrow();
        assertThat(product.getStatus()).isEqualTo(ProductStatus.PAYMENT_PENDING);
        assertThat(product.getReservedOrderId()).isEqualTo(orderId);
    }

    private Long saveActiveProduct() {
        Product product = Product.create(species, 1L, "몬스테라 중품", "설명", new BigDecimal("10000"),
                null, null, null, List.of());
        return productRepository.save(product).getId();
    }

    // 모든 스레드가 준비될 때까지 기다렸다가 동시에 출발시켜, 요청이 최대한 겹치게 만든다
    private <T> List<T> runConcurrently(IntFunction<Callable<T>> taskFactory) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < THREADS; i++) {
                Callable<T> task = taskFactory.apply(i);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS)); // 예외가 나면 테스트 실패
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
