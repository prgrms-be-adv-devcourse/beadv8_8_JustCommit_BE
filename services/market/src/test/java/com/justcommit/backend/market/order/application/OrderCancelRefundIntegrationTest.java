package com.justcommit.backend.market.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.market.OrderItemCancelledEvent;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.SellerOrderCancelledEvent;
import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.market.client.payment.PaymentUseCase;
import com.justcommit.backend.market.client.payment.RefundOutcome;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.order.application.OrderCancelTransactions.RefundClaim;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.event.OrderItemCancelled;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import com.justcommit.backend.market.shipment.application.ShipmentQueryService;
import com.justcommit.backend.market.shipment.application.ShipmentService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
import org.testcontainers.junit.jupiter.Testcontainers;

// 구매자 취소, 환불 재시도를 실제 PostgreSQL로 확인한다 (#282, ADR 0026).
// 환불은 PaymentUseCase 목으로 결과만 흉내 내고, 택배 취소 결과는 shipment_cancel_result 행을 직접 넣는다
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, OrderPaymentTransactions.class, OrderRecoveryService.class,
        OrderCancelTransactions.class, OrderCancelRefunder.class, ShipmentQueryService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 요청, 선점, 반영이 각자 트랜잭션을 열고 커밋한다
class OrderCancelRefundIntegrationTest {

    private static final long BUYER_ID = 10L;
    private static final long SELLER_ID = 20L;
    private static final BigDecimal PRICE = new BigDecimal("10000");
    private static final BigDecimal SHIPPING_FEE = new BigDecimal("3000");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @MockitoBean PaymentUseCase paymentUseCase;
    @MockitoBean OutboxPublisher outboxPublisher;
    @MockitoBean SellerOrderEventPublisher sellerOrderEventPublisher;
    @MockitoBean ShipmentService shipmentService;
    @MockitoBean OrderQuery orderQuery;
    @MockitoBean CartService cartService;

    @Autowired OrderCancelTransactions cancelTransactions;
    @Autowired OrderCancelRefunder refunder;
    @Autowired OrderRecoveryService recoveryService;
    @Autowired OrderPaymentTransactions paymentTransactions;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("truncate table shipment_cancel_result, shipment, orders_item, seller_order, orders");
    }

    @Test
    @DisplayName("[S-E11] 부분 취소의 환불 타임아웃 → 취소 중 유지 → 복구가 같은 키로 다시 요청해 성공 → 취소 완료 + 판매 재개 1건")
    void recoversRefundAfterTimeout() {
        Fixture fixture = paidOrder(2);
        Long itemId = fixture.itemIds().getFirst();
        when(paymentUseCase.refundCancelledItem(eq(itemId), eq(fixture.orderId()), eq(BUYER_ID), any()))
                .thenReturn(RefundOutcome.unknown("TIMEOUT"), RefundOutcome.refunded());

        itemCancel(itemId);

        assertThat(item(itemId)).containsEntry("status", "CANCEL_PROCESSING").containsEntry("last_error", "TIMEOUT")
                .containsEntry("attempt_count", 0);
        verify(outboxPublisher, never()).publish(any());

        passGrace(itemId);
        recoveryService.recoverProcessingRefunds();

        assertThat(item(itemId)).containsEntry("status", "CANCELLED").containsEntry("attempt_count", 1)
                .containsEntry("last_error", null);
        verify(paymentUseCase, times(2)).refundCancelledItem(eq(itemId), eq(fixture.orderId()), eq(BUYER_ID), any());
        verify(paymentUseCase, never()).refundShippingFee(any(), any(), any(), any());
        verify(outboxPublisher, times(1)).publish(new OrderItemCancelled(fixture.orderId(), itemId, 101L,
                OrderItemCancelled.CancelReason.BUYER));
        // [S-E02] 판매자 주문에 상품이 남으므로 판매자에게 일부 취소 알림
        verify(sellerOrderEventPublisher).publishAfterCommit(any(OrderItemCancelledEvent.class));
        assertThat(item(fixture.itemIds().get(1))).containsEntry("status", "NORMAL");
        assertThat(orderStatus(fixture.orderId())).isEqualTo("PAID");
    }

    @Test
    @DisplayName("[S-E12] 복구 5번까지 환불 결과 불명 → REVIEW_REQUIRED, 판매 재개 이벤트 없음")
    void requiresReviewAfterFiveUnknownRecoveries() {
        Fixture fixture = paidOrder(2);
        Long itemId = fixture.itemIds().getFirst();
        when(paymentUseCase.refundCancelledItem(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(RefundOutcome.unknown("HTTP_503"));

        itemCancel(itemId);
        for (int i = 0; i < 5; i++) {
            passGrace(itemId);
            recoveryService.recoverProcessingRefunds();
        }

        assertThat(item(itemId)).containsEntry("status", "REVIEW_REQUIRED").containsEntry("attempt_count", 5)
                .containsEntry("last_error", "HTTP_503");
        // 첫 호출 1번 + 복구 5번
        verify(paymentUseCase, times(6)).refundCancelledItem(anyLong(), anyLong(), anyLong(), any());
        verify(outboxPublisher, never()).publish(any());

        // 확인 필요가 된 주문상품은 복구가 더 집지 않는다
        passGrace(itemId);
        recoveryService.recoverProcessingRefunds();
        verify(paymentUseCase, times(6)).refundCancelledItem(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("[S-E12] 환불 확정 실패(4xx)는 재시도하지 않고 바로 REVIEW_REQUIRED")
    void requiresReviewWithoutRetryWhenRefundIsRejected() {
        Fixture fixture = paidOrder(2);
        Long itemId = fixture.itemIds().getFirst();
        when(paymentUseCase.refundCancelledItem(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(RefundOutcome.rejected("HTTP_409_REFUND_NOT_ALLOWED"));

        itemCancel(itemId);
        passGrace(itemId);
        recoveryService.recoverProcessingRefunds();

        assertThat(item(itemId)).containsEntry("status", "REVIEW_REQUIRED")
                .containsEntry("last_error", "HTTP_409_REFUND_NOT_ALLOWED").containsEntry("attempt_count", 0);
        verify(paymentUseCase, times(1)).refundCancelledItem(anyLong(), anyLong(), anyLong(), any());
        verify(outboxPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("[S-E07] 판매자 주문 전부 취소는 취소 중 + 배송 취소 대기로 끝내고, 택배 결과 전에는 환불, 판매 재개를 하지 않는다")
    void wholeCancelWaitsForCourierBeforeRefund() {
        Fixture fixture = paidOrder(2);

        cancelTransactions.requestCancel(BUYER_ID, fixture.orderId());
        recoveryService.recoverProcessingRefunds(); // 택배 결과를 기다리는 주문상품은 환불 복구 대상이 아니다

        fixture.itemIds().forEach(itemId -> assertThat(item(itemId))
                .containsEntry("status", "CANCEL_PROCESSING").containsEntry("attempted_at", null));
        assertThat(orderStatus(fixture.orderId())).isEqualTo("PAID");
        assertThat(settlementBaseAmount(fixture.sellerOrderId())).isEqualByComparingTo("0");
        verify(shipmentService).cancelShipment(fixture.sellerOrderId());
        verify(paymentUseCase, never()).refundCancelledItem(any(), any(), any(), any());
        verify(paymentUseCase, never()).refundShippingFee(any(), any(), any(), any());
        verify(outboxPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("[S-E01] 택배 취소됨 → 상품, 배송비 환불 후 취소 완료, 주문 취소, 판매 재개 주문상품마다, 판매자 전부 취소 알림")
    void refundsAfterCourierCancelled() {
        Fixture fixture = paidOrder(2);
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());
        cancelTransactions.requestCancel(BUYER_ID, fixture.orderId());
        Long resultId = courierResult(fixture, "CANCELLED");

        recoveryService.recoverCancelResults();

        fixture.itemIds().forEach(itemId -> assertThat(item(itemId)).containsEntry("status", "CANCELLED"));
        assertThat(orderStatus(fixture.orderId())).isEqualTo("CANCELLED");
        assertThat(processedAt(resultId)).isNotNull();
        verify(paymentUseCase).refundShippingFee(eq(fixture.sellerOrderId()), eq(fixture.orderId()), eq(BUYER_ID),
                argEquals(SHIPPING_FEE));
        for (int i = 0; i < 2; i++) {
            verify(paymentUseCase).refundCancelledItem(eq(fixture.itemIds().get(i)), eq(fixture.orderId()),
                    eq(BUYER_ID), argEquals(PRICE));
            verify(outboxPublisher).publish(new OrderItemCancelled(fixture.orderId(), fixture.itemIds().get(i),
                    101L + i, OrderItemCancelled.CancelReason.BUYER));
        }
        verify(sellerOrderEventPublisher, times(1)).publishAfterCommit(any(SellerOrderCancelledEvent.class));
    }

    @Test
    @DisplayName("[S-E13] 택배 취소 불가 → 환불 없이 주문상품을 정상으로 되돌리고 정산 기준액을 다시 계산")
    void revertsItemsWhenCourierLocked() {
        Fixture fixture = paidOrder(2);
        cancelTransactions.requestCancel(BUYER_ID, fixture.orderId());
        Long resultId = courierResult(fixture, "LOCKED");

        recoveryService.recoverCancelResults();

        fixture.itemIds().forEach(itemId -> assertThat(item(itemId)).containsEntry("status", "NORMAL"));
        assertThat(settlementBaseAmount(fixture.sellerOrderId())).isEqualByComparingTo("20000");
        assertThat(processedAt(resultId)).isNotNull();
        verify(paymentUseCase, never()).refundCancelledItem(any(), any(), any(), any());
        verify(paymentUseCase, never()).refundShippingFee(any(), any(), any(), any());
        verify(outboxPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("택배 취소됐는데 배송비 환불이 결과 불명이면 취소 중 유지, 복구가 상품, 배송비를 같은 키로 다시 요청해 완료")
    void retriesShippingFeeWithItemRefund() {
        Fixture fixture = paidOrder(1);
        Long itemId = fixture.itemIds().getFirst();
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.unknown("TIMEOUT"));
        cancelTransactions.requestCancel(BUYER_ID, fixture.orderId());
        courierResult(fixture, "CANCELLED");

        recoveryService.recoverCancelResults();
        assertThat(item(itemId)).containsEntry("status", "CANCEL_PROCESSING")
                .containsEntry("last_error", "SHIPPING_FEE_TIMEOUT");
        verify(outboxPublisher, never()).publish(any());

        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());
        passGrace(itemId);
        recoveryService.recoverProcessingRefunds();

        assertThat(item(itemId)).containsEntry("status", "CANCELLED");
        assertThat(orderStatus(fixture.orderId())).isEqualTo("CANCELLED");
        verify(paymentUseCase, times(2)).refundShippingFee(eq(fixture.sellerOrderId()), any(), any(), any());
        verify(outboxPublisher, times(1)).publish(any(OrderItemCancelled.class));
    }

    @Test
    @DisplayName("[S-E04] 같은 취소 두 번, 택배 결과 처리, 환불 복구를 연달아 두 번 돌려도 환불 반영과 이벤트는 한 번씩")
    void cancelAndRecoveryTwiceApplyOnce() {
        Fixture fixture = paidOrder(2);
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());

        cancelTransactions.requestCancel(BUYER_ID, fixture.orderId());
        cancelTransactions.requestCancel(BUYER_ID, fixture.orderId());
        cancelTransactions.requestItemCancel(BUYER_ID, fixture.itemIds().getFirst());
        courierResult(fixture, "CANCELLED");
        recoveryService.recoverCancelResults();
        recoveryService.recoverCancelResults();
        fixture.itemIds().forEach(this::passGrace);
        recoveryService.recoverProcessingRefunds();
        recoveryService.recoverProcessingRefunds();

        verify(shipmentService, times(1)).cancelShipment(fixture.sellerOrderId());
        verify(paymentUseCase, times(2)).refundCancelledItem(anyLong(), anyLong(), anyLong(), any());
        verify(paymentUseCase, times(1)).refundShippingFee(anyLong(), anyLong(), anyLong(), any());
        verify(outboxPublisher, times(2)).publish(any(OrderItemCancelled.class));
        verify(sellerOrderEventPublisher, times(1)).publishAfterCommit(any(SellerOrderCancelledEvent.class));

        // 늦게 온 같은 환불 결과는 이미 취소 완료라 반영하지 않는다
        Long itemId = fixture.itemIds().getFirst();
        RefundClaim late = new RefundClaim(itemId, fixture.orderId(), fixture.sellerOrderId(), BUYER_ID, 101L, PRICE,
                null, 1);
        assertThat(cancelTransactions.applyRefundOutcome(late, RefundOutcome.refunded())).isFalse();
        assertThat(cancelTransactions.applyRefundOutcome(late, RefundOutcome.rejected("HTTP_409"))).isFalse();
        assertThat(item(itemId)).containsEntry("status", "CANCELLED");
        verify(outboxPublisher, times(2)).publish(any(OrderItemCancelled.class));
    }

    @Test
    @DisplayName("배송비 0원인 판매자 주문 전부 취소: 배송 취소는 요청하고, 배송비 환불은 부르지 않고 상품 환불만으로 취소 완료")
    void freeShippingCancelsShipmentWithoutShippingFeeRefund() {
        Fixture fixture = paidOrder(1, BigDecimal.ZERO);
        Long itemId = fixture.itemIds().getFirst();
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.rejected("HTTP_400_INVALID_AMOUNT"));

        cancelTransactions.requestCancel(BUYER_ID, fixture.orderId());
        courierResult(fixture, "CANCELLED");
        recoveryService.recoverCancelResults();

        verify(shipmentService).cancelShipment(fixture.sellerOrderId());
        verify(paymentUseCase, never()).refundShippingFee(any(), any(), any(), any());
        assertThat(item(itemId)).containsEntry("status", "CANCELLED");
        assertThat(orderStatus(fixture.orderId())).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("배송비 환불 확정 실패(4xx): 상품 금액 환불이 성공했어도 배송비를 실은 주문상품 모두 REVIEW_REQUIRED, last_error는 SHIPPING_FEE_로 시작")
    void shippingFeeRejectionRequiresReviewWithShippingFeeReason() {
        Fixture fixture = paidOrder(2);
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.rejected("HTTP_400_INVALID_AMOUNT"));
        cancelTransactions.requestCancel(BUYER_ID, fixture.orderId());
        courierResult(fixture, "CANCELLED");

        recoveryService.recoverCancelResults();
        fixture.itemIds().forEach(this::passGrace);
        recoveryService.recoverProcessingRefunds();

        fixture.itemIds().forEach(itemId -> assertThat(item(itemId)).containsEntry("status", "REVIEW_REQUIRED")
                .containsEntry("last_error", "SHIPPING_FEE_HTTP_400_INVALID_AMOUNT"));
        assertThat(orderStatus(fixture.orderId())).isEqualTo("PAID");
        // 배송비 환불은 한 번만 부르고 재시도하지 않는다
        verify(paymentUseCase, times(1)).refundShippingFee(anyLong(), anyLong(), anyLong(), any());
        verify(outboxPublisher, never()).publish(any());
    }

    // OrderService.itemCancel과 같은 순서: 취소 중 커밋 → (트랜잭션 밖) 바로 환불
    private void itemCancel(Long itemId) {
        cancelTransactions.requestItemCancel(BUYER_ID, itemId).ifPresent(claim -> refunder.refund(List.of(claim)));
    }

    private void mockRefunds(RefundOutcome item, RefundOutcome shippingFee) {
        when(paymentUseCase.refundCancelledItem(anyLong(), anyLong(), anyLong(), any())).thenReturn(item);
        when(paymentUseCase.refundShippingFee(anyLong(), anyLong(), anyLong(), any())).thenReturn(shippingFee);
    }

    private static BigDecimal argEquals(BigDecimal expected) {
        return org.mockito.ArgumentMatchers.argThat(actual -> actual != null && actual.compareTo(expected) == 0);
    }

    // 결제된 주문 1건, 판매자 주문 1건, 주문상품 n건(상품 101, 102 …, 각 10,000원), 배송비 3,000원, 배송 READY
    private Fixture paidOrder(int itemCount) {
        return paidOrder(itemCount, SHIPPING_FEE);
    }

    private Fixture paidOrder(int itemCount, BigDecimal shippingFee) {
        Orders order = new Orders("ORD-cc-" + System.nanoTime(), BUYER_ID, "구매자", "01012345678", "12345", "서울시",
                null, itemCount);
        SellerOrder sellerOrder = new SellerOrder(order, SELLER_ID);
        sellerOrder.addItems(IntStream.range(0, itemCount)
                .mapToObj(i -> new OrdersItem(sellerOrder, 101L + i, "식물" + i, PRICE)).toList());
        sellerOrder.setShippingFee(shippingFee);
        order.addSellerOrders(List.of(sellerOrder));
        Long orderId = paymentTransactions.saveNewOrder(order).getId();
        jdbcTemplate.update("update orders set status = 'PAID' where id = ?", orderId);
        Long sellerOrderId = jdbcTemplate.queryForObject("select id from seller_order where order_id = ?", Long.class,
                orderId);
        List<Long> itemIds = jdbcTemplate.queryForList(
                "select id from orders_item where seller_order_id = ? order by id", Long.class, sellerOrderId);
        Long shipmentId = jdbcTemplate.queryForObject("""
                insert into shipment (seller_order_id, status, version, created_at, updated_at)
                values (?, 'READY', 0, now(), now()) returning id""", Long.class, sellerOrderId);
        return new Fixture(orderId, sellerOrderId, shipmentId, itemIds);
    }

    // 택배 취소 큐가 남기는 결과를 흉내 낸다: 배송 상태 + 결과 행
    private Long courierResult(Fixture fixture, String result) {
        jdbcTemplate.update("update shipment set status = ? where id = ?",
                result.equals("CANCELLED") ? "CANCELLED" : "CANCEL_LOCKED", fixture.shipmentId());
        return jdbcTemplate.queryForObject("""
                insert into shipment_cancel_result (shipment_id, seller_order_id, result, courier_return, decided_at,
                                                    created_at, updated_at)
                values (?, ?, ?, false, now(), now(), now()) returning id""",
                Long.class, fixture.shipmentId(), fixture.sellerOrderId(), result);
    }

    private void passGrace(Long itemId) {
        jdbcTemplate.update("update orders_item set attempted_at = ? where id = ? and attempted_at is not null",
                Timestamp.valueOf(LocalDateTime.now().minusSeconds(20)), itemId);
    }

    private Map<String, Object> item(Long itemId) {
        return jdbcTemplate.queryForMap(
                "select status, attempt_count, attempted_at, last_error from orders_item where id = ?", itemId);
    }

    private String orderStatus(Long orderId) {
        return jdbcTemplate.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private BigDecimal settlementBaseAmount(Long sellerOrderId) {
        return jdbcTemplate.queryForObject("select settlement_base_amount from seller_order where id = ?",
                BigDecimal.class, sellerOrderId);
    }

    private Object processedAt(Long resultId) {
        return jdbcTemplate.queryForObject("select processed_at from shipment_cancel_result where id = ?",
                Object.class, resultId);
    }

    private record Fixture(Long orderId, Long sellerOrderId, Long shipmentId, List<Long> itemIds) {
    }
}
