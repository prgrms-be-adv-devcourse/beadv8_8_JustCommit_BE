package com.justcommit.backend.market.order.application;

import com.justcommit.backend.market.shipment.application.ShipmentService;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.market.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.market.ReturnReason;
import com.justcommit.backend.market.ReturnRequestedEvent;
import com.justcommit.backend.market.ShipmentQuery;
import com.justcommit.backend.market.ShipmentResult;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.PurchaseConfirmedEventPublisher;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

// 반품 신청과 자동 구매 확정이 겹쳐도 하나만 반영되는지 실제 Postgres로 확인한다 (#382).
// 같은 반품 기간 상수를 써도 파드 시계가 다르면 둘 다 기간 판정을 통과할 수 있다. 그래서 배송 조회를 스레드마다 다르게 돌려줘
// 반품 쪽은 기간 안, 자동 구매 확정 쪽은 기간 지남으로 보이게 하고, 주문 행 잠금 + 일반 상태 재확인만으로 하나만 되는지 본다
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, OrderReturnService.class, OrderItemConfirmationService.class, SellerOrderCompletion.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderReturnConcurrencyIntegrationTest {

    private static final long BUYER_ID = 10L;
    private static final long HOLD_MILLIS = 300;
    private static final String RETURN_THREAD = "return-request";
    private static final String AUTO_THREAD = "auto-confirm";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @MockitoBean ShipmentQuery shipmentQuery;
    @MockitoBean SellerOrderEventPublisher sellerOrderEventPublisher;
    @MockitoBean PurchaseConfirmedEventPublisher purchaseConfirmedEventPublisher;
    @MockitoBean ShipmentService shipmentService;

    @Autowired OrderReturnService orderReturnService;
    @Autowired OrderItemConfirmationService confirmationService;
    @Autowired OrdersRepository ordersRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void skewedClocks() {
        // 반품 신청 파드는 배송 완료 1일 뒤, 자동 구매 확정 파드는 4일 뒤라고 본다
        when(shipmentQuery.findBySellerOrderId(anyLong())).thenAnswer(invocation -> {
            int days = Thread.currentThread().getName().startsWith(AUTO_THREAD) ? 4 : 1;
            return new ShipmentResult(1L, "DELIVERED", LocalDateTime.now().minusDays(days));
        });
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("truncate table return_request, orders_item, seller_order, orders");
    }

    @Test
    @DisplayName("자동 구매 확정이 먼저 주문을 잠그면 반품 신청은 잠금을 기다린 뒤 일반이 아니라 반품 불가(409)")
    void autoConfirmationLocksFirst() throws Exception {
        Item item = deliveredItem();
        CompletableFuture<Void> locked = new CompletableFuture<>();
        doAnswer(invocation -> {
            locked.complete(null);
            Thread.sleep(HOLD_MILLIS); // 주문 잠금을 쥔 채 머문다
            return null;
        }).when(sellerOrderEventPublisher).publishAfterCommit(any(OrderItemAutoConfirmedEvent.class));

        ExecutorService auto = Executors.newSingleThreadExecutor(r -> new Thread(r, AUTO_THREAD));
        try {
            Future<?> autoRun = auto.submit(() -> confirmationService.autoConfirmOrder(item.orderId(), List.of(item.itemId())));
            locked.get(5, TimeUnit.SECONDS);

            assertThatThrownBy(() -> orderReturnService.request(BUYER_ID, item.itemId(), ReturnReason.DAMAGED, null))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.RETURN_NOT_REQUESTABLE));
            autoRun.get(5, TimeUnit.SECONDS);
        } finally {
            auto.shutdownNow();
        }

        assertConfirmedOnly(item);
    }

    @Test
    @DisplayName("반품 신청이 먼저 주문을 잠그면 자동 구매 확정은 그 주문을 건너뛰고, 다음 주기에도 반품 신청이라 확정하지 않는다")
    void returnRequestLocksFirst() throws Exception {
        Item item = deliveredItem();
        CompletableFuture<Void> locked = new CompletableFuture<>();
        doAnswer(invocation -> {
            locked.complete(null);
            Thread.sleep(HOLD_MILLIS);
            return null;
        }).when(sellerOrderEventPublisher).publishAfterCommit(any(ReturnRequestedEvent.class));

        ExecutorService returner = Executors.newSingleThreadExecutor(r -> new Thread(r, RETURN_THREAD));
        ExecutorService auto = Executors.newSingleThreadExecutor(r -> new Thread(r, AUTO_THREAD));
        try {
            Future<?> returnRun = returner.submit(() ->
                    orderReturnService.request(BUYER_ID, item.itemId(), ReturnReason.DAMAGED, null));
            locked.get(5, TimeUnit.SECONDS);
            auto.submit(() -> confirmationService.autoConfirmOrder(item.orderId(), List.of(item.itemId())))
                    .get(5, TimeUnit.SECONDS);
            returnRun.get(5, TimeUnit.SECONDS);
            // 다음 주기: 잠금은 풀렸지만 일반이 아니라 건너뛴다
            auto.submit(() -> confirmationService.autoConfirmOrder(item.orderId(), List.of(item.itemId())))
                    .get(5, TimeUnit.SECONDS);
        } finally {
            returner.shutdownNow();
            auto.shutdownNow();
        }

        assertReturnRequestedOnly(item);
    }

    @Test
    @DisplayName("반품 신청과 자동 구매 확정을 같은 순간에 시작해도 매번 둘 중 하나만 반영된다")
    void onlyOneWinsWhenStartedTogether() throws Exception {
        for (int round = 0; round < 5; round++) {
            Item item = deliveredItem();
            CyclicBarrier start = new CyclicBarrier(2);
            ExecutorService returner = Executors.newSingleThreadExecutor(r -> new Thread(r, RETURN_THREAD));
            ExecutorService auto = Executors.newSingleThreadExecutor(r -> new Thread(r, AUTO_THREAD));
            try {
                Future<Throwable> returnRun = returner.submit(() -> {
                    start.await();
                    try {
                        orderReturnService.request(BUYER_ID, item.itemId(), ReturnReason.DAMAGED, null);
                        return null;
                    } catch (BusinessException e) {
                        return e;
                    }
                });
                Future<?> autoRun = auto.submit(() -> {
                    start.await();
                    confirmationService.autoConfirmOrder(item.orderId(), List.of(item.itemId()));
                    return null;
                });
                Throwable returnError = returnRun.get(10, TimeUnit.SECONDS);
                autoRun.get(10, TimeUnit.SECONDS);

                if (returnError == null) {
                    assertReturnRequestedOnly(item);
                } else {
                    assertThat(((BusinessException) returnError).getErrorCode())
                            .isEqualTo(OrderErrorCode.RETURN_NOT_REQUESTABLE);
                    assertConfirmedOnly(item);
                }
            } finally {
                returner.shutdownNow();
                auto.shutdownNow();
            }
            cleanUp();
        }
    }

    private void assertConfirmedOnly(Item item) {
        assertThat(itemStatus(item)).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from return_request", Long.class)).isZero();
    }

    private void assertReturnRequestedOnly(Item item) {
        assertThat(itemStatus(item)).isEqualTo("RETURN_REQUESTED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from return_request where order_item_id = ?",
                Long.class, item.itemId())).isEqualTo(1L);
    }

    private String itemStatus(Item item) {
        return jdbcTemplate.queryForObject("select status from orders_item where id = ?", String.class, item.itemId());
    }

    // 결제 완료 주문 1건, 주문상품 1건 (배송은 shipmentQuery 목으로 대신)
    private Item deliveredItem() {
        Orders order = new Orders("ORD-RR-" + System.nanoTime(), BUYER_ID, "구매자", "01012345678", "12345", "서울시", null, 1);
        SellerOrder sellerOrder = new SellerOrder(order, 20L);
        OrdersItem item = new OrdersItem(sellerOrder, 100L, "몬스테라", new BigDecimal("10000"));
        sellerOrder.addItems(List.of(item));
        order.addSellerOrders(List.of(sellerOrder));
        order.paid();
        ordersRepository.save(order);
        return new Item(order.getId(), item.getId());
    }

    private record Item(Long orderId, Long itemId) {
    }
}
