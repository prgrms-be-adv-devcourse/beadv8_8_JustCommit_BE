package com.justcommit.backend.market.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.OrderResult;
import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.market.client.payment.PaymentOutcome;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
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

// TX2 · TX3 조건부 UPDATE(CAS)를 실제 PostgreSQL에서 확인한다 (ADR 0023)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, OrderPaymentTransactions.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 각 단계가 자기 트랜잭션을 열고 커밋한다
class OrderPaymentTransactionsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean OutboxPublisher outboxPublisher;
    @MockitoBean OrderQuery orderQuery;
    @MockitoBean SellerOrderEventPublisher sellerOrderEventPublisher;
    @MockitoBean ShipmentService shipmentService;
    @MockitoBean CartService cartService;

    @Autowired OrderPaymentTransactions transactions;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("truncate table shipment, orders_item, seller_order, orders");
    }

    @Test
    @DisplayName("TX2: 기한 안의 결제 대기 주문은 결제 처리 중으로 바뀌고 시도 번호 · 시도 시각이 남는다")
    void startsAttemptWithinPaymentWindow() {
        Long orderId = saveOrder();

        OptionalInt attemptNo = transactions.startAttempt(orderId);

        assertThat(attemptNo).hasValue(1);
        Map<String, Object> row = row(orderId);
        assertThat(row.get("status")).isEqualTo("PAYMENT_PROCESSING");
        assertThat(row.get("attempt_no")).isEqualTo(1);
        assertThat(row.get("attempt_count")).isEqualTo(0);
        assertThat(row.get("attempted_at")).isNotNull();
    }

    @Test
    @DisplayName("[S-D10] TX2: 기한(10분)이 지난 결제 대기 주문은 바꾸지 않는다")
    void doesNotStartAttemptAfterPaymentWindow() {
        Long orderId = saveOrder();
        jdbcTemplate.update("update orders set created_at = ? where id = ?", LocalDateTime.now().minusMinutes(11), orderId);

        assertThat(transactions.startAttempt(orderId)).isEmpty();
        assertThat(row(orderId).get("status")).isEqualTo("PAYMENT_PENDING");
        assertThat(row(orderId).get("attempt_no")).isEqualTo(0);
    }

    @Test
    @DisplayName("TX2: 이미 결제 처리 중이면 두 번째 시작은 0건이라 결제를 부르지 않는다")
    void secondStartIsRejected() {
        Long orderId = saveOrder();
        transactions.startAttempt(orderId);

        assertThat(transactions.startAttempt(orderId)).isEmpty();
        assertThat(row(orderId).get("attempt_no")).isEqualTo(1);
    }

    @Test
    @DisplayName("TX3: 결제 완료면 PAID로 바꾸고 판매 완료 Outbox · 배송 행 생성을 같은 트랜잭션에서 한다")
    void appliesPaidOutcome() {
        Long orderId = saveOrder();
        int attemptNo = transactions.startAttempt(orderId).getAsInt();
        OrderResult result = new OrderResult(orderId, 10L, List.of(), null, null, null, null, null, null, null, null);
        when(orderQuery.getOrder(orderId)).thenReturn(result);

        assertThat(transactions.applyOutcome(orderId, attemptNo, PaymentOutcome.paid(), AttemptSource.FIRST_CALL)).isTrue();

        assertThat(row(orderId).get("status")).isEqualTo("PAID");
        verify(outboxPublisher).publish(new OrderPaid(orderId));
        verify(shipmentService).createShipments(result);
    }

    @Test
    @DisplayName("TX3: 결과 불명이면 결제 처리 중을 유지하고 last_error만 남긴다")
    void recordsUnknownOutcome() {
        Long orderId = saveOrder();
        int attemptNo = transactions.startAttempt(orderId).getAsInt();

        assertThat(transactions.applyOutcome(orderId, attemptNo, PaymentOutcome.unknown("TIMEOUT"),
                AttemptSource.FIRST_CALL)).isTrue();

        assertThat(row(orderId).get("status")).isEqualTo("PAYMENT_PROCESSING");
        assertThat(row(orderId).get("last_error")).isEqualTo("TIMEOUT");
        verify(outboxPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("TX3: 첫 호출의 연결 실패는 확정 실패 + order-failed, 복구의 연결 실패는 결과 불명")
    void notDeliveredDependsOnSource() {
        Long first = saveOrder();
        int firstAttempt = transactions.startAttempt(first).getAsInt();
        transactions.applyOutcome(first, firstAttempt, PaymentOutcome.notDelivered(), AttemptSource.FIRST_CALL);

        Long recovery = saveOrder();
        int recoveryAttempt = transactions.startAttempt(recovery).getAsInt();
        transactions.applyOutcome(recovery, recoveryAttempt, PaymentOutcome.notDelivered(), AttemptSource.RECOVERY);

        assertThat(row(first).get("status")).isEqualTo("PAYMENT_FAILED");
        assertThat(row(first).get("last_error")).isEqualTo("CONNECTION_FAILED");
        verify(outboxPublisher).publish(new OrderFailed(first));
        assertThat(row(recovery).get("status")).isEqualTo("PAYMENT_PROCESSING");
        assertThat(row(recovery).get("last_error")).isEqualTo("CONNECTION_FAILED");
        verify(outboxPublisher, never()).publish(new OrderFailed(recovery));
        // 첫 호출의 확정 실패는 장바구니를 복원하지 않는다 (create는 지우지 않았고 repayment는 장바구니를 바꾸지 않음)
        verify(cartService, never()).restoreItem(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("TX3: 시도 번호가 다르면(다른 시도가 선점) 늦게 온 결과는 아무것도 바꾸지 않는다")
    void ignoresStaleAttempt() {
        Long orderId = saveOrder();
        int attemptNo = transactions.startAttempt(orderId).getAsInt();
        jdbcTemplate.update("update orders set attempt_no = attempt_no + 1 where id = ?", orderId); // 복구가 선점

        assertThat(transactions.applyOutcome(orderId, attemptNo, PaymentOutcome.rejected("WALLET_NOT_FOUND"),
                AttemptSource.FIRST_CALL)).isFalse();

        assertThat(row(orderId).get("status")).isEqualTo("PAYMENT_PROCESSING");
        assertThat(row(orderId).get("last_error")).isNull();
        verify(outboxPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("TX3: 기한이 지났어도 결제됐으면 결제 완료로 처리한다")
    void appliesPaidEvenAfterPaymentWindow() {
        Long orderId = saveOrder();
        int attemptNo = transactions.startAttempt(orderId).getAsInt();
        jdbcTemplate.update("update orders set created_at = ? where id = ?", LocalDateTime.now().minusMinutes(30), orderId);
        when(orderQuery.getOrder(orderId)).thenReturn(
                new OrderResult(orderId, 10L, List.of(), null, null, null, null, null, null, null, null));

        assertThat(transactions.applyOutcome(orderId, attemptNo, PaymentOutcome.paid(), AttemptSource.RECOVERY)).isTrue();
        assertThat(row(orderId).get("status")).isEqualTo("PAID");
    }

    private Long saveOrder() {
        Orders order = new Orders("ORD-tx-" + System.nanoTime(), 10L, "구매자", "01012345678", "12345", "서울시", null, 1);
        SellerOrder sellerOrder = new SellerOrder(order, 20L);
        sellerOrder.addItems(List.of(new OrdersItem(sellerOrder, 101L, "몬스테라", new BigDecimal("10000"))));
        order.addSellerOrders(List.of(sellerOrder));
        return transactions.saveNewOrder(order).getId();
    }

    private Map<String, Object> row(Long orderId) {
        return jdbcTemplate.queryForMap(
                "select status, attempt_no, attempt_count, attempted_at, last_error from orders where id = ?", orderId);
    }
}
