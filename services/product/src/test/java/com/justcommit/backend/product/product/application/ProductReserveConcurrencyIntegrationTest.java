package com.justcommit.backend.product.product.application;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.ProductUseCase;
import com.justcommit.backend.product.client.member.MemberQuery;
import com.justcommit.backend.product.config.ClockConfig;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.image.application.ProductImageCleaner;
import com.justcommit.backend.product.image.application.ProductImageUrlValidator;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.product.presentation.dto.ProductRequest;
import com.justcommit.backend.product.reservation.infrastructure.ProductReservationRepository;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.infrastructure.SpeciesRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ClockConfig.class, ProductService.class})
@Testcontainers(disabledWithoutDocker = true)
// 테스트 메서드를 트랜잭션으로 감싸지 않음 → 준비 데이터가 실제로 커밋되어 다른 스레드에서도 보임
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductReserveConcurrencyIntegrationTest {

    private static final int THREADS = 10;
    private static final Long SELLER_ID = 1L;
    private static final BigDecimal PRICE = new BigDecimal("10000");

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
    @Autowired ProductService productService;
    @Autowired ProductRepository productRepository;
    @Autowired SpeciesRepository speciesRepository;
    @Autowired ProductReservationRepository reservationRepository;

    // 예약 흐름에서 쓰지 않는 ProductService 의존성은 목으로 대체
    @MockitoBean MemberQuery memberQuery;
    @MockitoBean ProductImageUrlValidator imageUrlValidator;
    @MockitoBean ProductImageCleaner imageCleaner;

    private Species species;

    @BeforeEach
    void setUp() {
        species = BeanUtils.instantiateClass(Species.class);
        // V4가 넣은 품종(몬스테라 등)과 이름이 겹치지 않게 테스트 전용 이름을 쓴다
        ReflectionTestUtils.setField(species, "name", "테스트 품종");
        speciesRepository.save(species);
    }

    @AfterEach
    void cleanUp() {
        productRepository.deleteAllInBatch();
        speciesRepository.deleteById(species.getId());   // V4 기준 데이터는 남기고 이 테스트가 만든 품종만 지움
        reservationRepository.deleteAllInBatch();   // 테스트마다 같은 orderId를 다시 쓰므로 예약 기록도 지움
    }

    @Test
    @DisplayName("여러 주문이 같은 상품을 동시에 예약하면 한 주문만 성공하고, 상품은 그 주문으로 예약된다")
    void onlyOneOrderReservesSameProductConcurrently() throws Exception {
        Long productId = saveActiveProduct();

        // 주문 100 ~ 109가 같은 상품을 동시에 예약
        List<Boolean> results = runConcurrently(
                i -> () -> reserves(100L + i, productId));

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
        assertThat(reserves(1L, taken)).isTrue();

        boolean reserved = reserves(2L, free1, taken, free2);

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
                i -> () -> reserves(100L + i, shared, own.get(i)));

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
        assertThat(reserves(orderId, productId)).isTrue();

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
        assertThat(reserves(expiredOrderId, productId)).isTrue();
        assertThat(productUseCase.release(expiredOrderId)).isTrue();
        assertThat(reserves(newOrderId, productId)).isTrue();

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

    @Test
    @DisplayName("판매자 삭제와 구매자 예약이 동시에 실행되면 한쪽만 반영된다")
    void onlyOneOfSellerDeleteAndReserveAppliesConcurrently() throws Exception {
        Long productId = saveActiveProduct();

        // 0번 스레드는 판매자 삭제, 나머지는 구매자 예약
        List<Boolean> results = runConcurrently(i -> i == 0
                ? () -> succeeds(() -> productService.deleteProduct(SELLER_ID, productId))
                : () -> reserves(100L + i, productId));

        // 삭제와 예약은 둘 다 판매 중일 때만 가능하므로, 먼저 잠금을 잡은 한 건만 성공
        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);

        Product product = productRepository.findById(productId).orElseThrow();
        if (results.get(0)) {
            assertThat(product.getStatus()).isEqualTo(ProductStatus.DELETED);
            assertThat(product.getReservedOrderId()).isNull();
        } else {
            assertReservedBy(productId, 100L + results.indexOf(true));
        }
    }

    @Test
    @DisplayName("판매자 수정과 구매자 예약이 동시에 실행돼도 수정이 예약 상태를 덮어쓰지 않는다")
    void sellerUpdateDoesNotOverwriteReservationConcurrently() throws Exception {
        Long productId = saveActiveProduct();
        // 가격은 그대로 두고 제목만 바꿈 (가격이 바뀌면 예약이 PRICE_CHANGED로 실패해 이 테스트의 관심사가 아님)
        ProductRequest request = new ProductRequest(species.getId(), "수정된 제목", "설명",
                PRICE, null, null, null, List.of());

        // 0번 스레드는 판매자 수정, 나머지는 구매자 예약
        List<Boolean> results = runConcurrently(i -> i == 0
                ? () -> succeeds(() -> productService.updateProduct(SELLER_ID, productId, request))
                : () -> reserves(100L + i, productId));

        // 예약은 항상 정확히 한 주문만 성공
        List<Boolean> reserveResults = results.subList(1, THREADS);
        assertThat(reserveResults).filteredOn(Boolean::booleanValue).hasSize(1);

        // 수정이 먼저든 나중이든, 예약 상태가 판매 중으로 되돌아가면 안 됨 (Lost Update 방지)
        Long winnerOrderId = 101L + reserveResults.indexOf(true);
        assertReservedBy(productId, winnerOrderId);

        // 수정이 먼저 잠금을 잡았으면 반영되고, 예약이 먼저면 수정 불가로 원래 제목 유지
        Product product = productRepository.findById(productId).orElseThrow();
        assertThat(product.getTitle()).isEqualTo(results.get(0) ? "수정된 제목" : "몬스테라 중품");
    }

    @Test
    @DisplayName("주문서 가격이 하나라도 다르면 PRICE_CHANGED로 아무것도 예약하지 않는다")
    void reservesNothingWhenAnyPriceChanged() {
        Long same = saveActiveProduct();
        Long changed = saveActiveProduct();
        Map<Long, BigDecimal> expectedPrices = new LinkedHashMap<>();
        expectedPrices.put(same, PRICE);
        expectedPrices.put(changed, new BigDecimal("9000"));

        assertThatThrownBy(() -> productUseCase.reserve(1L, expectedPrices))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRICE_CHANGED);

        assertActive(same);
        assertActive(changed);
        // 실패한 예약은 기록도 남지 않아 같은 orderId로 다시 시도할 수 있음
        assertThat(reserves(1L, same, changed)).isTrue();
    }

    @Test
    @DisplayName("같은 orderId로 다시 예약하면 그대로 성공하고 상태는 바뀌지 않는다")
    void sameOrderIdReservesAgainIdempotently() {
        Long productId = saveActiveProduct();
        assertThat(reserves(1L, productId)).isTrue();

        assertThat(reserves(1L, productId)).isTrue();

        assertReservedBy(productId, 1L);
    }

    @Test
    @DisplayName("해제된 orderId로 늦게 온 예약은 RESERVATION_RELEASED로 거절한다")
    void rejectsLateReservationOfReleasedOrder() {
        Long productId = saveActiveProduct();
        assertThat(reserves(1L, productId)).isTrue();
        assertThat(productUseCase.release(1L)).isTrue();

        assertThatThrownBy(() -> productUseCase.reserve(1L, Map.of(productId, PRICE)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.RESERVATION_RELEASED);
        assertActive(productId);
    }

    @Test
    @DisplayName("예약 응답 타임아웃으로 해제가 먼저 오면, 그 뒤에 도착한 예약은 거절한다")
    void rejectsReservationArrivingAfterRelease() {
        Long productId = saveActiveProduct();
        // market이 예약 응답을 못 받아 주문을 실패로 끝냄 → 해제가 예약보다 먼저 도착
        assertThat(productUseCase.release(1L)).isFalse();

        assertThat(reserves(1L, productId)).isFalse();
        assertActive(productId);
    }

    @Test
    @DisplayName("판매 재개: 구매자 취소면 판매 완료 상품을 판매 중으로 되돌리고 숨기지 않는다")
    void releaseItemAfterBuyerCancelKeepsVisible() {
        Long productId = saveActiveProduct();
        assertThat(reserves(1L, productId)).isTrue();
        assertThat(productUseCase.markSoldOut(1L)).isTrue();

        assertThat(productUseCase.releaseItem(productId, 1L, false)).isTrue();

        assertActive(productId);
        assertThat(productRepository.findById(productId).orElseThrow().isHidden()).isFalse();
    }

    @Test
    @DisplayName("판매 재개: 판매자 거절 · 반품이면 판매 중으로 되돌리고 숨긴다")
    void releaseItemWithHideHidesProduct() {
        Long productId = saveActiveProduct();
        assertThat(reserves(1L, productId)).isTrue();
        assertThat(productUseCase.markSoldOut(1L)).isTrue();

        assertThat(productUseCase.releaseItem(productId, 1L, true)).isTrue();

        assertActive(productId);
        assertThat(productRepository.findById(productId).orElseThrow().isHidden()).isTrue();
    }

    @Test
    @DisplayName("판매 재개: 취소가 판매 완료보다 먼저 와도 결제 대기 상품을 되돌리고, 늦은 판매 완료는 0건이다")
    void releaseItemBeforeSoldOutThenLateSoldOutIsNoop() {
        Long productId = saveActiveProduct();
        assertThat(reserves(1L, productId)).isTrue();

        assertThat(productUseCase.releaseItem(productId, 1L, false)).isTrue();
        assertThat(productUseCase.markSoldOut(1L)).isFalse();

        assertActive(productId);
    }

    @Test
    @DisplayName("판매 재개: 이미 다른 주문에 다시 팔린 상품이나 같은 이벤트가 다시 오면 아무것도 바꾸지 않는다")
    void releaseItemIgnoresOtherOrderAndDuplicates() {
        Long productId = saveActiveProduct();
        assertThat(reserves(1L, productId)).isTrue();
        assertThat(productUseCase.markSoldOut(1L)).isTrue();
        assertThat(productUseCase.releaseItem(productId, 1L, false)).isTrue();

        // 주문 2가 다시 사서 판매 완료
        assertThat(reserves(2L, productId)).isTrue();
        assertThat(productUseCase.markSoldOut(2L)).isTrue();

        // 주문 1의 취소 이벤트가 한 번 더 와도(중복) 주문 2의 판매는 그대로
        assertThat(productUseCase.releaseItem(productId, 1L, true)).isFalse();

        Product product = productRepository.findById(productId).orElseThrow();
        assertThat(product.getStatus()).isEqualTo(ProductStatus.SOLD_OUT);
        assertThat(product.getReservedOrderId()).isEqualTo(2L);
        assertThat(product.isHidden()).isFalse();
    }

    // 실패를 예외로 알리는 동작을, 다른 결과와 비교할 수 있게 성공 여부로 바꾼다
    private boolean succeeds(Runnable action) {
        try {
            action.run();
            return true;
        } catch (BusinessException e) {
            return false;
        }
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

    // 주문서 가격이 현재 가격과 같은 예약. 409(예약 불가 · 가격 변경 · 해제된 주문)면 false
    private boolean reserves(Long orderId, Long... productIds) {
        Map<Long, BigDecimal> expectedPrices = new LinkedHashMap<>();
        for (Long productId : productIds) {
            expectedPrices.put(productId, PRICE);
        }
        return succeeds(() -> productUseCase.reserve(orderId, expectedPrices));
    }

    // 검수를 통과해 판매 중인 상품 (검수 통과 메서드는 #343에서 생김)
    private Long saveActiveProduct() {
        Product product = Product.create(species, SELLER_ID, "몬스테라 중품", "설명", PRICE,
                null, null, null, List.of());
        ReflectionTestUtils.setField(product, "status", ProductStatus.ACTIVE);
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

    @ParameterizedTest
    @EnumSource(value = ProductStatus.class, names = {"REVIEW_PENDING", "REJECTED"})
    @DisplayName("검수 중 · 반려 상품은 예약할 수 없어 PRODUCT_NOT_RESERVABLE(409), 상태도 그대로")
    void cannotReserveInReviewProduct(ProductStatus status) {
        Product product = Product.create(species, SELLER_ID, "몬스테라 중품", "설명", PRICE,
                null, null, null, List.of());
        ReflectionTestUtils.setField(product, "status", status);
        Long productId = productRepository.save(product).getId();

        assertThatThrownBy(() -> productUseCase.reserve(1L, Map.of(productId, PRICE)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ProductErrorCode.PRODUCT_NOT_RESERVABLE);
        assertThat(productRepository.findById(productId).orElseThrow().getStatus()).isEqualTo(status);
    }
}
