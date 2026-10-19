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
