package com.justcommit.backend.market.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.OrderResult;
import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.market.client.payment.PaymentOutcome;
import com.justcommit.backend.market.client.payment.PaymentUseCase;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.order.application.OrderPaymentTransactions.AttemptSource;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.event.OrderFailed;
import com.justcommit.backend.market.order.event.OrderPaid;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import com.justcommit.backend.market.shipment.application.ShipmentService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Saga 복구 스케줄러를 실제 PostgreSQL로 확인한다 (ADR 0023). 결제는 PaymentUseCase 목으로 결과만 흉내 낸다
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, OrderPaymentTransactions.class, OrderRecoveryService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 선점 · 반영이 각자 트랜잭션을 열고 커밋한다
class OrderRecoveryIntegrationTest {

    private static final long BUYER_ID = 10L;
    private static final BigDecimal AMOUNT = new BigDecimal("13000");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean PaymentUseCase paymentUseCase;
    @MockitoBean OutboxPublisher outboxPublisher;
    @MockitoBean OrderQuery orderQuery;
    @MockitoBean SellerOrderEventPublisher sellerOrderEventPublisher;
    @MockitoBean ShipmentService shipmentService;
    @MockitoBean CartService cartService;

    @Autowired OrderRecoveryService recoveryService;
    @Autowired OrderPaymentTransactions transactions;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("truncate table shipment, orders_item, seller_order, orders");
    }

    @Test
    @DisplayName("결과 불명 주문을 같은 orderId로 다시 불러 결제됐으면 결제 완료 + 판매 완료 이벤트")
    void recoversPaidOrder() {
        Long orderId = processingOrder(1, 0, 20);
        when(paymentUseCase.pay(orderId, BUYER_ID, AMOUNT)).thenReturn(PaymentOutcome.paid());
        when(orderQuery.getOrder(orderId)).thenReturn(orderResult(orderId));

        recoveryService.recoverProcessingPayments();

        assertThat(row(orderId)).containsEntry("status", "PAID")
                .containsEntry("attempt_no", 2).containsEntry("attempt_count", 1);
        verify(outboxPublisher).publish(new OrderPaid(orderId));
        verify(cartService, never()).restoreItem(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("복구 결과가 잔액 부족이면 결제 대기로 되돌린다")
    void returnsToPendingWhenBalanceIsInsufficient() {
        Long orderId = processingOrder(1, 0, 20);
        when(paymentUseCase.pay(orderId, BUYER_ID, AMOUNT)).thenReturn(PaymentOutcome.insufficientBalance());

        recoveryService.recoverProcessingPayments();

        assertThat(row(orderId)).containsEntry("status", "PAYMENT_PENDING");
        verify(outboxPublisher, never()).publish(any());
        verify(cartService, never()).restoreItem(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("복구 중 4xx(401 · 403 포함)는 확정 실패: 주문 실패 + order-failed + 장바구니 복원")
    void failsOrderWhenRecoveryIsRejected() {
        Long unauthorized = processingOrder(1, 0, 20);
        Long forbidden = processingOrder(1, 0, 20);
        when(paymentUseCase.pay(unauthorized, BUYER_ID, AMOUNT)).thenReturn(PaymentOutcome.rejected("HTTP_401"));
        when(paymentUseCase.pay(forbidden, BUYER_ID, AMOUNT)).thenReturn(PaymentOutcome.rejected("HTTP_403_FORBIDDEN"));

        recoveryService.recoverProcessingPayments();

        assertThat(row(unauthorized)).containsEntry("status", "PAYMENT_FAILED").containsEntry("last_error", "HTTP_401");
        assertThat(row(forbidden)).containsEntry("status", "PAYMENT_FAILED");
        verify(outboxPublisher).publish(new OrderFailed(unauthorized));
        verify(outboxPublisher).publish(new OrderFailed(forbidden));
        // create가 지운 주문상품을 주문 당시 가격으로 되돌린다 (주문마다 한 번)
        verify(cartService, times(2)).restoreItem(eq(BUYER_ID), eq(101L), argThat(price -> price.compareTo(new BigDecimal("10000")) == 0));
    }

    @Test
    @DisplayName("장바구니 복원이 실패하면 주문 실패도 함께 되돌려 결제 처리 중으로 남기고 다음 주기에 다시 시도한다")
    void keepsProcessingWhenCartRestoreFails() {
        Long orderId = processingOrder(1, 0, 20);
        when(paymentUseCase.pay(orderId, BUYER_ID, AMOUNT)).thenReturn(PaymentOutcome.rejected("WALLET_NOT_FOUND"));
        doThrow(new IllegalStateException("cart down")).when(cartService).restoreItem(anyLong(), anyLong(), any());

        recoveryService.recoverProcessingPayments();

        assertThat(row(orderId)).containsEntry("status", "PAYMENT_PROCESSING")
                .containsEntry("attempt_no", 2).containsEntry("attempt_count", 1);
        assertThat(row(orderId).get("last_error")).isNull();
    }

    @Test
    @DisplayName("복구 중 연결 실패는 결과 불명: 결제 처리 중 유지 + last_error, order-failed 없음")
    void keepsProcessingWhenRecoveryIsNotDelivered() {
        Long orderId = processingOrder(1, 0, 20);
        when(paymentUseCase.pay(orderId, BUYER_ID, AMOUNT)).thenReturn(PaymentOutcome.notDelivered());

        recoveryService.recoverProcessingPayments();

        assertThat(row(orderId)).containsEntry("status", "PAYMENT_PROCESSING")
                .containsEntry("last_error", "CONNECTION_FAILED").containsEntry("attempt_count", 1);
        verify(outboxPublisher, never()).publish(any());
        verify(cartService, never()).restoreItem(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("마지막 시도에서 10초가 안 지난 주문은 아직 진행 중일 수 있어 건드리지 않는다")
    void skipsOrderWithinGrace() {
        Long orderId = processingOrder(1, 0, 5);

        recoveryService.recoverProcessingPayments();

        assertThat(row(orderId)).containsEntry("attempt_no", 1).containsEntry("attempt_count", 0);
        verify(paymentUseCase, never()).pay(any(), any(), any());
    }

    @Test
    @DisplayName("5번째 복구도 결과 불명이면 그 자리에서 확인 필요로 바꾸고 시도 번호를 올린다")
    void requiresReviewWhenLastRecoveryIsUnknown() {
        Long orderId = processingOrder(5, 4, 20);
        when(paymentUseCase.pay(orderId, BUYER_ID, AMOUNT)).thenReturn(PaymentOutcome.unknown("TIMEOUT"));

        recoveryService.recoverProcessingPayments();

        // 선점 +1(6) → 확인 필요 전환 +1(7)
        assertThat(row(orderId)).containsEntry("status", "REVIEW_REQUIRED").containsEntry("attempt_no", 7)
                .containsEntry("attempt_count", 5).containsEntry("last_error", "TIMEOUT");
        verify(outboxPublisher, never()).publish(any());
        verify(cartService, never()).restoreItem(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("5번째 선점 뒤 반영 없이 유예가 지나면 결제를 부르지 않고 확인 필요, 그 뒤 늦은 응답은 반영하지 않는다")
    void requiresReviewWhenLastClaimWasNeverApplied() {
        Long orderId = processingOrder(6, 5, 20); // 5번째 선점(attempt_no 6) 뒤 파드가 죽음

        recoveryService.recoverProcessingPayments();

        assertThat(row(orderId)).containsEntry("status", "REVIEW_REQUIRED").containsEntry("attempt_no", 7);
        verify(paymentUseCase, never()).pay(any(), any(), any());

        // 죽은 파드가 보냈던 5번째 시도의 결과가 늦게 도착
        assertThat(transactions.applyOutcome(orderId, 6, PaymentOutcome.paid(), AttemptSource.RECOVERY)).isFalse();
        assertThat(row(orderId)).containsEntry("status", "REVIEW_REQUIRED");
        verify(outboxPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("늦게 온 이전 시도 결과는 복구가 선점한 새 시도를 덮지 않는다")
    void staleResultDoesNotOverwriteNewAttempt() {
        Long orderId = processingOrder(1, 0, 20);
        when(paymentUseCase.pay(orderId, BUYER_ID, AMOUNT)).thenAnswer(invocation -> {
            // 복구가 시도 2로 선점한 뒤, 첫 호출(시도 1)의 확정 실패 응답이 늦게 도착
            assertThat(transactions.applyOutcome(orderId, 1, PaymentOutcome.rejected("WALLET_NOT_FOUND"),
                    AttemptSource.FIRST_CALL)).isFalse();
            return PaymentOutcome.unknown("TIMEOUT");
        });

        recoveryService.recoverProcessingPayments();

        assertThat(row(orderId)).containsEntry("status", "PAYMENT_PROCESSING")
                .containsEntry("attempt_no", 2).containsEntry("last_error", "TIMEOUT");
        verify(outboxPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("두 파드가 동시에 복구를 돌려도 주문마다 결제를 한 번만 다시 부른다")
    void twoPodsRecoverEachOrderOnce() throws Exception {
        List<Long> orderIds = IntStream.range(0, 4).mapToObj(i -> processingOrder(1, 0, 20)).toList();
        Queue<Long> called = new ConcurrentLinkedQueue<>();
        Set<String> pods = ConcurrentHashMap.newKeySet();
        when(paymentUseCase.pay(anyLong(), eq(BUYER_ID), any())).thenAnswer(invocation -> {
            called.add(invocation.getArgument(0));
            pods.add(Thread.currentThread().getName());
            Thread.sleep(300); // 결제 응답을 기다리는 동안 다른 파드가 같은 주문을 만난다
            return PaymentOutcome.unknown("TIMEOUT");
        });

        runOnTwoPods(recoveryService::recoverProcessingPayments);

        assertThat(called).containsExactlyInAnyOrderElementsOf(orderIds);
        assertThat(pods).hasSize(2);
        assertThat(jdbcTemplate.queryForList("select attempt_count from orders", Integer.class)).containsOnly(1);
        verify(paymentUseCase, times(orderIds.size())).pay(anyLong(), eq(BUYER_ID), any());
    }

    // 결제 처리 중 주문을 만든다: 시도 번호 · 복구 횟수 · 마지막 시도 몇 초 전
    private Long processingOrder(int attemptNo, int attemptCount, int attemptedSecondsAgo) {
        Orders order = new Orders("ORD-rc-" + System.nanoTime(), BUYER_ID, "구매자", "01012345678", "12345", "서울시", null, 1);
        SellerOrder sellerOrder = new SellerOrder(order, 20L);
        sellerOrder.addItems(List.of(new OrdersItem(sellerOrder, 101L, "몬스테라", new BigDecimal("10000"))));
        sellerOrder.setShippingFee(new BigDecimal("3000"));
        order.addSellerOrders(List.of(sellerOrder));
        Long orderId = transactions.saveNewOrder(order).getId();
        jdbcTemplate.update("""
                        update orders set status = 'PAYMENT_PROCESSING', attempt_no = ?, attempt_count = ?, attempted_at = ?
                         where id = ?""",
                attemptNo, attemptCount, Timestamp.valueOf(LocalDateTime.now().minusSeconds(attemptedSecondsAgo)), orderId);
        return orderId;
    }

    private Map<String, Object> row(Long orderId) {
        return jdbcTemplate.queryForMap(
                "select status, attempt_no, attempt_count, last_error from orders where id = ?", orderId);
    }

    private static OrderResult orderResult(Long orderId) {
        return new OrderResult(orderId, BUYER_ID, List.of(), null, null, null, null, null, null, null, null);
    }

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
