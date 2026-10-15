package com.justcommit.backend.market;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.cart.domain.Cart;
import com.justcommit.backend.market.cart.domain.CartItem;
import com.justcommit.backend.market.cart.infrastructure.CartItemRepository;
import com.justcommit.backend.market.cart.infrastructure.CartRepository;
import com.justcommit.backend.market.order.application.OrderItemConfirmationService;
import com.justcommit.backend.market.order.application.OrderService;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.infrastructure.AutoConfirmTarget;
import com.justcommit.backend.market.order.infrastructure.OrderPaymentTtlStore;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.PurchaseConfirmedEventPublisher;
import com.justcommit.backend.market.order.infrastructure.scheduler.OrderScheduler;
import com.justcommit.backend.market.order.presentation.CartOrderCreateRequest;
import com.justcommit.backend.market.order.presentation.OrderCreateResponse;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingRequest;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingResponse;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import com.justcommit.backend.member.MemberAddress;
import com.justcommit.backend.member.MemberQuery;
import com.justcommit.backend.payment.PaymentResult;
import com.justcommit.backend.payment.PaymentUseCase;
import com.justcommit.backend.payment.application.PaymentService;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import com.justcommit.backend.product.ProductQuery;
import com.justcommit.backend.product.ProductResult;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.product.application.ProductService;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.infrastructure.SpeciesRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("test")
@Import(OrderPaymentIntegrationTest.QueryConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class OrderPaymentIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    // Redis 키의 커밋 시점은 OrderPaymentTtlStoreTest에서 별도로 검증한다.
    @MockitoBean OrderPaymentTtlStore paymentTtlStore;
    @MockitoBean CourierClient courierClient;
    @MockitoBean MemberQuery memberQuery;
    @MockitoBean PurchaseConfirmedEventPublisher purchaseConfirmedEventPublisher;

    @Autowired OrderService orderService;
    @Autowired OrderItemConfirmationService orderItemConfirmationService;
    @Autowired OrderScheduler orderScheduler;
    @Autowired OrdersRepository ordersRepository;
    @Autowired OrdersItemRepository ordersItemRepository;
    @Autowired SellerOrderRepository sellerOrderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired SpeciesRepository speciesRepository;
    @Autowired CartRepository cartRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository walletTransactionRepository;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired InterceptingProductQuery productQuery;
    @Autowired InterceptingPaymentUseCase paymentUseCase;

    @AfterEach
    void clearQueryHook() {
        productQuery.afterLookup = () -> {};
        paymentUseCase.beforePayment = () -> {};
    }

    @Test
    void successfulPaymentUpdatesOrderProductWalletAndTransaction() {
        stubShipmentBooking();
        long buyerId = 101L;
        Product product = product(201L);
        Long cartItemId = cartItem(buyerId, product);
        wallet(buyerId, "20000");

        OrderCreateResponse response = orderService.create(buyerId, request(cartItemId));

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(ordersRepository.findById(response.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStatus()).isEqualTo(ProductStatus.SOLD_OUT);
        assertThat(balance(buyerId)).isEqualByComparingTo("7000");
        assertThat(paymentCount(response.orderId())).isEqualTo(1);
        assertThat(cartItemRepository.existsById(cartItemId)).isFalse();
        assertThat(shipmentsForOrder(response.orderId()))
                .singleElement().satisfies(shipment -> assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.BOOKED));
    }

    @Test
    void insufficientBalanceThenRechargeAndRetryDebitsOnlyOnce() throws Exception {
        stubShipmentBooking();
        long buyerId = 102L;
        Product product = product(202L);
        Long cartItemId = cartItem(buyerId, product);
        wallet(buyerId, "0");

        OrderCreateResponse pending = orderService.create(buyerId, request(cartItemId));

        assertThat(pending.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.PAYMENT_PENDING);
        assertThat(paymentCount(pending.orderId())).isZero();
        assertThat(cartItemRepository.existsById(cartItemId)).isFalse();
        assertThat(shipmentsForOrder(pending.orderId())).isEmpty();

        Wallet charged = walletRepository.findByMemberId(buyerId).orElseThrow();
        charged.credit(new BigDecimal("20000"));
        walletRepository.saveAndFlush(charged);

        List<OrderCreateResponse> retries = concurrently(
                () -> orderService.repayment(buyerId, pending.orderId()),
                () -> orderService.repayment(buyerId, pending.orderId()));
        assertThat(retries).extracting(OrderCreateResponse::status).containsExactly(OrderStatus.PAID, OrderStatus.PAID);
        assertThat(orderService.repayment(buyerId, pending.orderId()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(ordersRepository.findById(pending.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStatus()).isEqualTo(ProductStatus.SOLD_OUT);
        assertThat(balance(buyerId)).isEqualByComparingTo("7000");
        assertThat(paymentCount(pending.orderId())).isEqualTo(1);
        assertThat(shipmentsForOrder(pending.orderId())).hasSize(1);
    }

    @Test
    void expiredOrderCannotBeDebitedWhileExpirationAndRetryRunConcurrently() throws Exception {
        long buyerId = 103L;
        Product product = product(203L);
        Long cartItemId = cartItem(buyerId, product);
        wallet(buyerId, "0");
        OrderCreateResponse pending = orderService.create(buyerId, request(cartItemId));

        Wallet charged = walletRepository.findByMemberId(buyerId).orElseThrow();
        charged.credit(new BigDecimal("20000"));
        walletRepository.saveAndFlush(charged);
        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(11)), pending.orderId());

        List<Boolean> results = concurrently(
                () -> {
                    try {
                        orderService.repayment(buyerId, pending.orderId());
                        return true;
                    } catch (BusinessException expected) {
                        return false;
                    }
                },
                () -> {
                    orderService.expirePendingOrders();
                    return true;
                });

        assertThat(results).containsExactlyInAnyOrder(false, true);
        assertThat(ordersRepository.findById(pending.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(balance(buyerId)).isEqualByComparingTo("20000");
        assertThat(paymentCount(pending.orderId())).isZero();
    }

    @Test
    void paymentHoldingOrderLockWinsWhenExpirationWindowPasses() throws Exception {
        stubShipmentBooking();
        long buyerId = 107L;
        Product product = product(207L);
        Long cartItemId = cartItem(buyerId, product);
        wallet(buyerId, "0");
        OrderCreateResponse pending = orderService.create(buyerId, request(cartItemId));

        Wallet charged = walletRepository.findByMemberId(buyerId).orElseThrow();
        charged.credit(new BigDecimal("20000"));
        walletRepository.saveAndFlush(charged);
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(4);
        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(expiresAt.minusMinutes(10)), pending.orderId());

        CountDownLatch paymentReached = new CountDownLatch(1);
        CountDownLatch releasePayment = new CountDownLatch(1);
        paymentUseCase.beforePayment = () -> {
            paymentReached.countDown();
            try {
                if (!releasePayment.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("결제 재개 시간 초과");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<OrderCreateResponse> payment = executor.submit(() -> orderService.repayment(buyerId, pending.orderId()));
            assertThat(paymentReached.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(Math.max(0, Duration.between(LocalDateTime.now(), expiresAt.plusNanos(100_000_000)).toMillis()));
            Future<?> expiration = executor.submit(orderService::expirePendingOrders);
            Thread.sleep(100); // 만료 조회가 주문 잠금을 기다리도록 결제 완료 전 시작한다.
            releasePayment.countDown();
            assertThat(payment.get(10, TimeUnit.SECONDS).status()).isEqualTo(OrderStatus.PAID);
            expiration.get(10, TimeUnit.SECONDS);
        } finally {
            releasePayment.countDown();
            executor.shutdownNow();
        }

        assertThat(ordersRepository.findById(pending.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStatus()).isEqualTo(ProductStatus.SOLD_OUT);
        assertThat(balance(buyerId)).isEqualByComparingTo("7000");
        assertThat(paymentCount(pending.orderId())).isEqualTo(1);
    }

    @Test
    void onlyOneOfTwoConcurrentOrdersCanReserveTheSameProduct() throws Exception {
        long firstBuyerId = 104L;
        long secondBuyerId = 105L;
        Product product = product(204L);
        Long firstCartItemId = cartItem(firstBuyerId, product);
        Long secondCartItemId = cartItem(secondBuyerId, product);
        wallet(firstBuyerId, "0");
        wallet(secondBuyerId, "0");

        List<OrderAttempt> attempts = concurrently(
                () -> attempt(firstBuyerId, firstCartItemId),
                () -> attempt(secondBuyerId, secondCartItemId));

        assertThat(attempts).filteredOn(OrderAttempt::succeeded).hasSize(1);
        assertThat(attempts).filteredOn(attempt -> !attempt.succeeded()).hasSize(1);
        OrderAttempt winner = attempts.stream().filter(OrderAttempt::succeeded).findFirst().orElseThrow();
        OrderAttempt loser = attempts.stream().filter(attempt -> !attempt.succeeded()).findFirst().orElseThrow();
        Product reserved = productRepository.findById(product.getId()).orElseThrow();

        assertThat(ordersRepository.findAll()).filteredOn(order ->
                order.getBuyerId().equals(firstBuyerId) || order.getBuyerId().equals(secondBuyerId)).hasSize(1);
        assertThat(reserved.getStatus()).isEqualTo(ProductStatus.PAYMENT_PENDING);
        assertThat(reserved.getReservedOrderId()).isEqualTo(winner.orderId());
        assertThat(ordersRepository.findById(winner.orderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(cartItemRepository.existsById(loser.cartItemId())).isTrue();
        assertThat(cartItemRepository.existsById(winner.cartItemId())).isFalse();
        assertThat(paymentCount(winner.orderId())).isZero();
    }

    @Test
    void partialReservationFailureRollsBackOrderAndKeepsCart() {
        long buyerId = 106L;
        Product available = product(205L);
        Product takenAfterLookup = product(206L);
        Long firstCartItemId = cartItem(buyerId, available);
        Long secondCartItemId = cartItem(buyerId, takenAfterLookup);
        wallet(buyerId, "20000");

        // 상품 조회 뒤 다른 트랜잭션이 한 상품을 먼저 선점한 상황을 재현한다.
        productQuery.afterLookup = () -> {
            TransactionTemplate otherTransaction = new TransactionTemplate(transactionManager);
            otherTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            otherTransaction.executeWithoutResult(status -> assertThat(
                    productRepository.reserve(9000L, List.of(takenAfterLookup.getId()))).isEqualTo(1));
        };

        assertThatThrownBy(() -> orderService.create(buyerId, request(firstCartItemId, secondCartItemId)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.PRODUCT_UNAVAILABLE));

        assertThat(ordersRepository.findAll()).filteredOn(order -> order.getBuyerId().equals(buyerId)).isEmpty();
        assertThat(productRepository.findById(available.getId()).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.ACTIVE);
        Product taken = productRepository.findById(takenAfterLookup.getId()).orElseThrow();
        assertThat(taken.getStatus()).isEqualTo(ProductStatus.PAYMENT_PENDING);
        assertThat(taken.getReservedOrderId()).isEqualTo(9000L);
        assertThat(cartItemRepository.existsById(firstCartItemId)).isTrue();
        assertThat(cartItemRepository.existsById(secondCartItemId)).isTrue();
        assertThat(balance(buyerId)).isEqualByComparingTo("20000");
        Long walletId = walletRepository.findByMemberId(buyerId).orElseThrow().getId();
        assertThat(walletTransactionRepository.findAll()).noneMatch(transaction ->
                transaction.getWallet().getId().equals(walletId));
    }

    @Test
    void concurrentConfirmationOfDifferentSellerOrdersCompletesParentOrder() throws Exception {
        stubShipmentBooking();
        long buyerId = 108L;
        Long firstCartItemId = cartItem(buyerId, product(208L));
        Long secondCartItemId = cartItem(buyerId, product(209L));
        wallet(buyerId, "30000");
        Long orderId = orderService.create(buyerId, request(firstCartItemId, secondCartItemId)).orderId();
        List<Long> sellerOrderIds = sellerOrderRepository.findByOrder_Id(orderId).stream()
                .map(sellerOrder -> sellerOrder.getId()).toList();
        assertThat(sellerOrderIds).hasSize(2);
        List<Long> itemIds = ordersItemRepository.findBySellerOrderIds(sellerOrderIds).stream()
                .map(item -> item.getId()).toList();
        jdbcTemplate.update("UPDATE shipment SET status = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP " +
                "WHERE seller_order_id IN (?, ?)", sellerOrderIds.get(0), sellerOrderIds.get(1));

        // 부모 주문 잠금이 없으면 두 요청이 이 지점에 함께 도달해 서로의 미커밋 변경을 놓친다.
        CountDownLatch bothReady = new CountDownLatch(2);
        doAnswer(invocation -> {
            bothReady.countDown();
            // 부모 주문 잠금이 있으면 두 번째 요청은 여기까지 오지 못하므로 짧게 기다린 뒤 진행한다.
            bothReady.await(1, TimeUnit.SECONDS);
            return null;
        }).when(purchaseConfirmedEventPublisher).publishAfterCommit(any(PurchaseConfirmedEvent.class));

        concurrently(
                () -> { orderService.confirm(buyerId, itemIds.get(0)); return true; },
                () -> { orderService.confirm(buyerId, itemIds.get(1)); return true; });

        assertThat(ordersItemRepository.findAllById(itemIds))
                .allSatisfy(item -> assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED));
        assertThat(sellerOrderRepository.findAllById(sellerOrderIds))
                .allSatisfy(sellerOrder -> assertThat(sellerOrder.getConfirmedAt()).isNotNull());
        verify(purchaseConfirmedEventPublisher, times(2)).publishAfterCommit(any(PurchaseConfirmedEvent.class));
        assertThat(ordersRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void concurrentConfirmationOfSameItemPublishesSettlementOnce() throws Exception {
        stubShipmentBooking();
        long buyerId = 109L;
        Long cartItemId = cartItem(buyerId, product(210L));
        wallet(buyerId, "20000");
        Long orderId = orderService.create(buyerId, request(cartItemId)).orderId();
        Long sellerOrderId = sellerOrderRepository.findByOrder_Id(orderId).getFirst().getId();
        Long itemId = ordersItemRepository.findBySellerOrderIds(List.of(sellerOrderId)).getFirst().getId();
        jdbcTemplate.update("UPDATE shipment SET status = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP " +
                "WHERE seller_order_id = ?", sellerOrderId);

        concurrently(
                () -> { orderService.confirm(buyerId, itemId); return true; },
                () -> { orderService.confirm(buyerId, itemId); return true; });

        assertThat(ordersRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(purchaseConfirmedEventPublisher, times(1)).publishAfterCommit(any(PurchaseConfirmedEvent.class));
    }

    @Test
    void autoConfirmsOnlyAfterThreeDaysFromDelivery() {
        stubShipmentBooking();
        long buyerId = 110L;
        Long cartItemId = cartItem(buyerId, product(211L));
        wallet(buyerId, "20000");
        Long orderId = orderService.create(buyerId, request(cartItemId)).orderId();
        Long sellerOrderId = sellerOrderRepository.findByOrder_Id(orderId).getFirst().getId();
        Long itemId = ordersItemRepository.findBySellerOrderIds(List.of(sellerOrderId)).getFirst().getId();
        jdbcTemplate.update("UPDATE shipment SET status = 'DELIVERED', delivered_at = ? WHERE seller_order_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(2)), sellerOrderId);

        orderScheduler.autoConfirmDeliveredItems();
        assertThat(ordersItemRepository.findById(itemId).orElseThrow().getStatus()).isEqualTo(OrderItemStatus.NORMAL);

        jdbcTemplate.update("UPDATE shipment SET delivered_at = ? WHERE seller_order_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(4)), sellerOrderId);
        assertThat(ordersItemRepository.findAutoConfirmTargets(OrderStatus.PAID, OrderItemStatus.NORMAL,
                ShipmentStatus.DELIVERED, LocalDateTime.now().minusDays(3)))
                .contains(new AutoConfirmTarget(orderId, sellerOrderId, itemId));

        orderScheduler.autoConfirmDeliveredItems();
        assertThat(ordersItemRepository.findById(itemId).orElseThrow().getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(sellerOrderRepository.findById(sellerOrderId).orElseThrow().getConfirmedAt()).isNotNull();
        assertThat(ordersRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(purchaseConfirmedEventPublisher, times(1)).publishAfterCommit(any(PurchaseConfirmedEvent.class));

        orderScheduler.autoConfirmDeliveredItems();
        verify(purchaseConfirmedEventPublisher, times(1)).publishAfterCommit(any(PurchaseConfirmedEvent.class));
    }

    @Test
    void autoConfirmsRemainingItemAfterOneItemWasManuallyConfirmed() {
        stubShipmentBooking();
        long buyerId = 112L;
        Long firstCartItemId = cartItem(buyerId, product(213L));
        Long secondCartItemId = cartItem(buyerId, product(213L));
        wallet(buyerId, "30000");
        Long orderId = orderService.create(buyerId, request(firstCartItemId, secondCartItemId)).orderId();
        Long sellerOrderId = sellerOrderRepository.findByOrder_Id(orderId).getFirst().getId();
        List<Long> itemIds = ordersItemRepository.findBySellerOrderIds(List.of(sellerOrderId)).stream()
                .map(item -> item.getId()).toList();
        assertThat(itemIds).hasSize(2);
        jdbcTemplate.update("UPDATE shipment SET status = 'DELIVERED', delivered_at = ? WHERE seller_order_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(4)), sellerOrderId);

        orderService.confirm(buyerId, itemIds.get(0));
        assertThat(sellerOrderRepository.findById(sellerOrderId).orElseThrow().getConfirmedAt()).isNull();
        assertThat(ordersRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        verify(purchaseConfirmedEventPublisher, times(0)).publishAfterCommit(any(PurchaseConfirmedEvent.class));

        orderScheduler.autoConfirmDeliveredItems();

        assertThat(ordersItemRepository.findAllById(itemIds))
                .allSatisfy(item -> assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CONFIRMED));
        assertThat(sellerOrderRepository.findById(sellerOrderId).orElseThrow().getConfirmedAt()).isNotNull();
        assertThat(ordersRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(purchaseConfirmedEventPublisher, times(1)).publishAfterCommit(any(PurchaseConfirmedEvent.class));
    }

    @Test
    void autoConfirmationExcludesCancelledItemAndSettlesOnlyRemainingItem() {
        stubShipmentBooking();
        long buyerId = 113L;
        Long firstCartItemId = cartItem(buyerId, product(214L));
        Long secondCartItemId = cartItem(buyerId, product(214L));
        wallet(buyerId, "30000");
        Long orderId = orderService.create(buyerId, request(firstCartItemId, secondCartItemId)).orderId();
        Long sellerOrderId = sellerOrderRepository.findByOrder_Id(orderId).getFirst().getId();
        List<Long> itemIds = ordersItemRepository.findBySellerOrderIds(List.of(sellerOrderId)).stream()
                .map(item -> item.getId()).toList();
        assertThat(itemIds).hasSize(2);
        jdbcTemplate.update("UPDATE shipment SET status = 'DELIVERED', delivered_at = ? WHERE seller_order_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(4)), sellerOrderId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ordersItemRepository.findById(itemIds.get(0)).orElseThrow().cancel();
            sellerOrderRepository.findById(sellerOrderId).orElseThrow().calculateSettlementBaseAmount();
        });

        assertThat(ordersItemRepository.findAutoConfirmTargets(OrderStatus.PAID, OrderItemStatus.NORMAL,
                ShipmentStatus.DELIVERED, LocalDateTime.now().minusDays(3)))
                .contains(new AutoConfirmTarget(orderId, sellerOrderId, itemIds.get(1)))
                .doesNotContain(new AutoConfirmTarget(orderId, sellerOrderId, itemIds.get(0)));

        orderScheduler.autoConfirmDeliveredItems();

        assertThat(ordersItemRepository.findById(itemIds.get(0)).orElseThrow().getStatus())
                .isEqualTo(OrderItemStatus.CANCELLED);
        assertThat(ordersItemRepository.findById(itemIds.get(1)).orElseThrow().getStatus())
                .isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(sellerOrderRepository.findById(sellerOrderId).orElseThrow().getSettlementBaseAmount())
                .isEqualByComparingTo("10000");
        assertThat(ordersRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        ArgumentCaptor<PurchaseConfirmedEvent> event = ArgumentCaptor.forClass(PurchaseConfirmedEvent.class);
        verify(purchaseConfirmedEventPublisher, times(1)).publishAfterCommit(event.capture());
        assertThat(event.getValue().saleAmount()).isEqualByComparingTo("10000");
    }

    @Test
    void manualAndAutomaticConfirmationOfSameItemPublishOnce() throws Exception {
        stubShipmentBooking();
        long buyerId = 111L;
        Long cartItemId = cartItem(buyerId, product(212L));
        wallet(buyerId, "20000");
        Long orderId = orderService.create(buyerId, request(cartItemId)).orderId();
        Long sellerOrderId = sellerOrderRepository.findByOrder_Id(orderId).getFirst().getId();
        Long itemId = ordersItemRepository.findBySellerOrderIds(List.of(sellerOrderId)).getFirst().getId();
        jdbcTemplate.update("UPDATE shipment SET status = 'DELIVERED', delivered_at = ? WHERE seller_order_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(4)), sellerOrderId);

        concurrently(
                () -> { orderService.confirm(buyerId, itemId); return true; },
                () -> { orderItemConfirmationService.autoConfirmOrderItem(itemId); return true; });

        assertThat(ordersItemRepository.findById(itemId).orElseThrow().getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(ordersRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(purchaseConfirmedEventPublisher, times(1)).publishAfterCommit(any(PurchaseConfirmedEvent.class));
    }

    private Product product(long sellerId) {
        Species species = BeanUtils.instantiateClass(Species.class);
        ReflectionTestUtils.setField(species, "name", "integration-" + UUID.randomUUID());
        speciesRepository.saveAndFlush(species);
        return productRepository.saveAndFlush(Product.create(
                species, sellerId, "몬스테라", "테스트 상품", new BigDecimal("10000"),
                null, null, null, List.of()));
    }

    private Long cartItem(long buyerId, Product product) {
        Cart cart = cartRepository.findByMemberId(buyerId)
                .orElseGet(() -> cartRepository.saveAndFlush(new Cart(buyerId)));
        return cartItemRepository.saveAndFlush(new CartItem(cart, product.getId(), product.getPrice())).getId();
    }

    private void wallet(long buyerId, String balance) {
        Wallet wallet = new Wallet(buyerId);
        if (new BigDecimal(balance).signum() > 0) {
            wallet.credit(new BigDecimal(balance));
        }
        walletRepository.saveAndFlush(wallet);
    }

    private BigDecimal balance(long buyerId) {
        return walletRepository.findByMemberId(buyerId).orElseThrow().getBalance();
    }

    private long paymentCount(long orderId) {
        return walletTransactionRepository.findAll().stream()
                .filter(transaction -> transaction.getRefType() == RefType.PAYMENT)
                .filter(transaction -> transaction.getStatus() == TransactionStatus.OUT) // 구매자 출금 내역만 카운트하고 HOLDING 지갑 내역은 제외한다.
                .filter(transaction -> transaction.getRefId().equals(orderId))
                .count();
    }

    private List<Shipment> shipmentsForOrder(Long orderId) {
        return shipmentRepository.findBySellerOrderIdIn(sellerOrderRepository.findByOrder_Id(orderId).stream()
                .map(sellerOrder -> sellerOrder.getId()).toList());
    }

    private void stubShipmentBooking() {
        when(memberQuery.getDefaultAddress(anyLong())).thenReturn(new MemberAddress(
                1L, "판매자", "010-1111-2222", "06236", "서울", null));
        when(courierClient.book(anyString(), any(CourierBookingRequest.class))).thenAnswer(invocation ->
                new CourierBookingResponse("tracking-" + invocation.getArgument(0), invocation.getArgument(0), "RESERVED",
                        OffsetDateTime.now()));
    }

    private CartOrderCreateRequest request(Long... cartItemIds) {
        return new CartOrderCreateRequest(List.of(cartItemIds), "구매자", "010-1234-5678", "06236", "서울", null);
    }

    private OrderAttempt attempt(long buyerId, Long cartItemId) {
        try {
            return new OrderAttempt(buyerId, cartItemId, orderService.create(buyerId, request(cartItemId)).orderId());
        } catch (BusinessException expected) {
            return new OrderAttempt(buyerId, cartItemId, null);
        }
    }

    @SafeVarargs
    private static <T> List<T> concurrently(Callable<T>... tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch ready = new CountDownLatch(tasks.length);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("동시 실행 준비 시간 초과");
                    }
                    return task.call();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private record OrderAttempt(long buyerId, Long cartItemId, Long orderId) {
        boolean succeeded() {
            return orderId != null;
        }
    }

    @TestConfiguration
    static class QueryConfig {
        @Bean
        @Primary
        InterceptingProductQuery interceptingProductQuery(ProductService productService) {
            return new InterceptingProductQuery(productService);
        }

        @Bean
        @Primary
        InterceptingPaymentUseCase interceptingPaymentUseCase(PaymentService paymentService) {
            return new InterceptingPaymentUseCase(paymentService);
        }
    }

    static class InterceptingProductQuery implements ProductQuery {
        private final ProductService delegate;
        volatile Runnable afterLookup = () -> {};

        InterceptingProductQuery(ProductService delegate) {
            this.delegate = delegate;
        }

        @Override
        public ProductResult getProduct(Long productId) {
            return delegate.getProduct(productId);
        }

        @Override
        public List<ProductResult> getProducts(List<Long> productIds) {
            List<ProductResult> result = delegate.getProducts(productIds);
            afterLookup.run();
            return result;
        }
    }

    static class InterceptingPaymentUseCase implements PaymentUseCase {
        private final PaymentService delegate;
        volatile Runnable beforePayment = () -> {};

        InterceptingPaymentUseCase(PaymentService delegate) {
            this.delegate = delegate;
        }

        @Override
        public PaymentResult pay(Long orderId, Long buyerId, BigDecimal amount) {
            beforePayment.run();
            return delegate.pay(orderId, buyerId, amount);
        }

        @Override
        public void distributeSettlement(Long settlementId, Long sellerId,
                                         BigDecimal payoutAmount, BigDecimal fee) {
            delegate.distributeSettlement(settlementId, sellerId, payoutAmount, fee);
        }

        @Override
        public PaymentResult refundCancelledItem(Long orderItemId, Long buyerId, BigDecimal amount) {
            return delegate.refundCancelledItem(orderItemId, buyerId, amount);
        }

        @Override
        public PaymentResult refundShippingFee(Long sellerOrderId, Long buyerId, BigDecimal amount) {
            return delegate.refundShippingFee(sellerOrderId, buyerId, amount);
        }

    }
}
