package com.justcommit.backend.market.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.justcommit.backend.market.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.PurchaseConfirmedEvent;
import com.justcommit.backend.market.ShipmentQuery;
import com.justcommit.backend.market.ShipmentResult;
import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.order.event.OrderExpired;
import com.justcommit.backend.market.client.payment.PaymentUseCase;
import com.justcommit.backend.market.client.product.ProductQuery;
import com.justcommit.backend.market.client.product.ProductUseCase;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.PurchaseConfirmedEventPublisher;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import com.justcommit.backend.market.shipment.application.ShipmentService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// market 파드 2개가 같은 스케줄러를 동시에 돌려도, SKIP LOCKED로 주문을 나눠 갖고 같은 주문은 한 번만 처리하는지 실제 Postgres로 확인한다
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, OrderService.class, OrderExpirationService.class, OrderItemConfirmationService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 스케줄러가 주문마다 자기 트랜잭션을 열고 커밋한다
class OrderSchedulerConcurrencyIntegrationTest {

    private static final int ORDERS = 4;
    private static final long BUYER_ID = 10L;
    // 주문을 잡은 채 머무는 시간. 그 사이 다른 파드가 같은 주문을 만나 건너뛰게 한다.
    private static final long HOLD_MILLIS = 300;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean CartService cartService;
    @MockitoBean MemberQuery memberQuery;
    @MockitoBean ProductQuery productQuery;
    @MockitoBean ProductUseCase productUseCase;
    @MockitoBean OutboxPublisher outboxPublisher;
    @MockitoBean PaymentUseCase paymentUseCase;
    @MockitoBean OrderQuery orderQuery;
    @MockitoBean ShipmentService shipmentService;
    @MockitoBean ShipmentQuery shipmentQuery;
    @MockitoBean SellerOrderEventPublisher sellerOrderEventPublisher;
    @MockitoBean PurchaseConfirmedEventPublisher purchaseConfirmedEventPublisher;

    @Autowired OrderService orderService;
    @Autowired OrderExpirationService orderExpirationService;
    @Autowired OrdersRepository ordersRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("truncate table shipment, orders_item, seller_order, orders");
    }

    @Test
    @DisplayName("결제 대기 만료를 두 파드가 동시에 돌려도 주문마다 한 번만 만료 · 예약 해제하고, 주문을 나눠 처리한다")
    void expiresEachOrderOnceWhenTwoPodsRunTogether() throws Exception {
        List<Long> orderIds = saveOrders(false);
        jdbcTemplate.update("update orders set created_at = ?", LocalDateTime.now().minusMinutes(11));
        Queue<Long> released = new ConcurrentLinkedQueue<>();
        Set<String> pods = ConcurrentHashMap.newKeySet();
        doAnswer(invocation -> {
            released.add(invocation.<OrderExpired>getArgument(0).orderId());
            pods.add(Thread.currentThread().getName());
            Thread.sleep(HOLD_MILLIS);
            return null;
        }).when(outboxPublisher).publish(any(OrderExpired.class));

        runOnTwoPods(orderService::expirePendingOrders);

        assertThat(released).containsExactlyInAnyOrderElementsOf(orderIds);
        assertThat(pods).hasSize(2);
        assertThat(jdbcTemplate.queryForList("select status from orders", String.class))
                .hasSize(ORDERS).containsOnly("EXPIRED");
        verify(cartService, times(ORDERS * 2)).restoreItem(eq(BUYER_ID), anyLong(), any());
    }

    @Test
    @DisplayName("재결제 · 취소 요청이 잡고 있는 주문은 기다리지 않고 건너뛰어 다음 주기에 다시 본다")
    void skipsOrderLockedByBuyerRequestWithoutWaiting() {
        Long orderId = saveOrders(false).getFirst();
        jdbcTemplate.update("update orders set created_at = ?", LocalDateTime.now().minusMinutes(11));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ordersRepository.findLockedById(orderId).orElseThrow(); // 구매자 요청처럼 주문을 잡고 있는다
            assertThatCode(() -> CompletableFuture.runAsync(() -> orderExpirationService.expire(orderId))
                    .get(2, TimeUnit.SECONDS)).doesNotThrowAnyException();
        });

        assertThat(jdbcTemplate.queryForObject("select status from orders where id = ?", String.class, orderId))
                .isEqualTo("PAYMENT_PENDING");
        verify(outboxPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("자동 구매 확정을 두 파드가 동시에 돌려도 주문상품마다 한 번만 확정하고, 정산 이벤트는 판매자 주문마다 한 번만 낸다")
    void autoConfirmsEachItemOnceWhenTwoPodsRunTogether() throws Exception {
        saveOrders(true);
        List<Long> sellerOrderIds = jdbcTemplate.queryForList("select id from seller_order", Long.class);
        sellerOrderIds.forEach(sellerOrderId -> jdbcTemplate.update(
                "insert into shipment (seller_order_id, status, delivered_at, version) values (?, 'DELIVERED', ?, 0)",
                sellerOrderId, LocalDateTime.now().minusDays(4)));
        List<Long> itemIds = jdbcTemplate.queryForList("select id from orders_item", Long.class);
        when(shipmentQuery.findBySellerOrderId(anyLong()))
                .thenReturn(new ShipmentResult(1L, "DELIVERED", LocalDateTime.now().minusDays(4)));
        Queue<Long> confirmedItems = new ConcurrentLinkedQueue<>();
        Queue<Long> settledSellerOrders = new ConcurrentLinkedQueue<>();
        Set<String> pods = ConcurrentHashMap.newKeySet();
        doAnswer(invocation -> {
            confirmedItems.add(invocation.<OrderItemAutoConfirmedEvent>getArgument(0).orderItemId());
            pods.add(Thread.currentThread().getName());
            Thread.sleep(HOLD_MILLIS / 2);
            return null;
        }).when(sellerOrderEventPublisher).publishAfterCommit(any(OrderItemAutoConfirmedEvent.class));
        doAnswer(invocation -> settledSellerOrders.add(invocation.<PurchaseConfirmedEvent>getArgument(0).sellerOrderId()))
                .when(purchaseConfirmedEventPublisher).publishAfterCommit(any(PurchaseConfirmedEvent.class));

        runOnTwoPods(orderService::autoConfirmDeliveredItems);

        assertThat(confirmedItems).containsExactlyInAnyOrderElementsOf(itemIds);
        assertThat(settledSellerOrders).containsExactlyInAnyOrderElementsOf(sellerOrderIds);
        assertThat(pods).hasSize(2);
        assertThat(jdbcTemplate.queryForList("select status from orders_item", String.class)).containsOnly("CONFIRMED");
        assertThat(jdbcTemplate.queryForList("select status from orders", String.class)).containsOnly("COMPLETED");
    }

    // 주문 ORDERS건, 각 판매자 주문 1건 · 주문상품 2건
    private List<Long> saveOrders(boolean paid) {
        return IntStream.range(0, ORDERS).mapToObj(i -> {
            Orders order = new Orders("ORD-scheduler-" + i, BUYER_ID, "구매자", "01012345678", "12345", "서울시", null, 2);
            SellerOrder sellerOrder = new SellerOrder(order, 20L + i);
            sellerOrder.addItems(List.of(new OrdersItem(sellerOrder, 100L + i * 2, "몬스테라", new BigDecimal("10000")),
                    new OrdersItem(sellerOrder, 101L + i * 2, "선인장", new BigDecimal("5000"))));
            order.addSellerOrders(List.of(sellerOrder));
            if (paid) {
                order.paid();
            }
            return ordersRepository.save(order).getId();
        }).toList();
    }

    // 파드 두 개가 같은 순간에 스케줄러를 시작한다
    private void runOnTwoPods(Runnable scheduler) throws Exception {
        ExecutorService pods = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Void> pod = () -> {
            start.await();
            scheduler.run();
            return null;
        };
        try {
            for (Future<Void> run : pods.invokeAll(List.of(pod, pod), 30, TimeUnit.SECONDS)) {
                run.get();
            }
        } finally {
            pods.shutdownNow();
        }
    }
}
