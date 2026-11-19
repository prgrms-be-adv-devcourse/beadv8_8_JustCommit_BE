package com.justcommit.backend.market.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.market.OrderItemCancelledEvent;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.SellerOrderAcceptedEvent;
import com.justcommit.backend.market.SellerOrderCancelledEvent;
import com.justcommit.backend.market.SellerOrderRejectReason;
import com.justcommit.backend.market.SellerOrderRejectedEvent;
import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.market.client.payment.PaymentUseCase;
import com.justcommit.backend.market.client.payment.RefundOutcome;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.market.client.product.ProductQuery;
import com.justcommit.backend.market.client.product.ProductUseCase;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.order.application.OrderCancelTransactions.RefundClaim;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.event.OrderItemCancelled;
import com.justcommit.backend.market.order.event.OrderItemCancelled.CancelReason;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import com.justcommit.backend.market.shipment.application.ShipmentQueryService;
import com.justcommit.backend.market.shipment.application.ShipmentService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
import org.testcontainers.junit.jupiter.Testcontainers;

// 판매자 수락, 거절, 24시간 자동 거절과 수락 전 구매자 취소를 실제 PostgreSQL로 확인한다 (#284, #285, ADR 0013).
// 환불은 PaymentUseCase 목으로 결과만 흉내 낸다. 배송 생성, 취소는 ShipmentService 목으로 호출만 본다
// (배송 행 자체는 ShipmentCreateIntegrationTest)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, OrderPaymentTransactions.class, OrderRecoveryService.class,
        OrderCancelTransactions.class, OrderCancelRefunder.class, SellerOrderDecisionTransactions.class,
        ShipmentQueryService.class, OrderService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 수락, 거절, 취소, 반영이 각자 트랜잭션을 열고 커밋한다
class SellerOrderDecisionIntegrationTest {

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
    // OrderService.autoRejectExpiredSellerOrders만 쓰므로 나머지 의존성은 목으로 둔다
    @MockitoBean MemberQuery memberQuery;
    @MockitoBean ProductQuery productQuery;
    @MockitoBean ProductUseCase productUseCase;
    @MockitoBean OrderItemConfirmationService orderItemConfirmationService;
    @MockitoBean OrderExpirationService orderExpirationService;

    @Autowired SellerOrderDecisionTransactions decisions;
    @Autowired OrderCancelTransactions cancelTransactions;
    @Autowired OrderCancelRefunder refunder;
    @Autowired OrderRecoveryService recoveryService;
    @Autowired OrderPaymentTransactions paymentTransactions;
    @Autowired OrderService orderService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("truncate table shipment_cancel_result, shipment, orders_item, seller_order, orders");
    }

    @Test
    @DisplayName("[S-D08] 전체 수락: 수락 대기 상품이 모두 NORMAL, 모든 상품을 담은 배송 생성, 정산 기준액 유지, 환불 없음. 다시 수락하면 409")
    void acceptsAllItems() {
        Fixture fixture = pendingOrder(2);

        decisions.accept(SELLER_ID, fixture.sellerOrderId());

        assertThat(itemStatuses(fixture)).containsOnly("NORMAL");
        verify(shipmentService, times(1)).createShipment(fixture.sellerOrderId(), List.of("식물0", "식물1"));
        assertThat(settlementBaseAmount(fixture.sellerOrderId())).isEqualByComparingTo("20000");
        verify(paymentUseCase, never()).refundCancelledItem(any(), any(), any(), any());
        assertNotAcceptable(() -> decisions.accept(SELLER_ID, fixture.sellerOrderId()));
    }

    @Test
    @DisplayName("[S-D08] 일부 거절: 고른 상품만 SELLER 사유로 환불, 판매 재개 SELLER, 나머지는 NORMAL이고 그 상품만 배송, 배송비 환불 없음, 판매자에게 구매자 취소 알림 없음")
    void rejectsSomeItems() {
        Fixture fixture = pendingOrder(2);
        Long rejected = fixture.itemIds().getFirst();
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());

        reject(fixture, List.of(rejected));

        assertThat(item(rejected)).containsEntry("status", "CANCELLED").containsEntry("cancel_reason", "SELLER");
        assertThat(item(fixture.itemIds().get(1))).containsEntry("status", "NORMAL");
        verify(shipmentService, times(1)).createShipment(fixture.sellerOrderId(), List.of("식물1"));
        assertThat(settlementBaseAmount(fixture.sellerOrderId())).isEqualByComparingTo("10000");
        verify(paymentUseCase, never()).refundShippingFee(any(), any(), any(), any());
        verify(outboxPublisher).publish(new OrderItemCancelled(fixture.orderId(), rejected, 101L, CancelReason.SELLER));
        verify(sellerOrderEventPublisher, never()).publishAfterCommit(any(OrderItemCancelledEvent.class));
        verify(sellerOrderEventPublisher, never()).publishAfterCommit(any(SellerOrderCancelledEvent.class));
        assertThat(orderStatus(fixture.orderId())).isEqualTo("PAID");
    }

    @Test
    @DisplayName("전부 거절: 모든 상품과 배송비를 환불하고 주문은 CANCELLED, 배송은 만들거나 취소하지 않는다")
    void rejectsAllItemsWithShippingFee() {
        Fixture fixture = pendingOrder(2);
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());

        reject(fixture, fixture.itemIds());

        assertThat(itemStatuses(fixture)).containsOnly("CANCELLED");
        verify(paymentUseCase, times(1)).refundShippingFee(eq(fixture.sellerOrderId()), eq(fixture.orderId()),
                eq(BUYER_ID), argEquals(SHIPPING_FEE));
        verify(outboxPublisher, times(2)).publish(any(OrderItemCancelled.class));
        verify(shipmentService, never()).createShipment(any(), any());
        verify(shipmentService, never()).cancelShipment(any());
        assertThat(orderStatus(fixture.orderId())).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("수락 전에 구매자가 상품 하나를 취소한 뒤 수락하면 남은 상품만 배송한다")
    void acceptAfterBuyerItemCancelShipsRemainingOnly() {
        Fixture fixture = pendingOrder(2);
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());
        itemCancel(fixture.itemIds().getFirst());

        decisions.accept(SELLER_ID, fixture.sellerOrderId());

        assertThat(itemStatuses(fixture)).containsExactly("CANCELLED", "NORMAL");
        verify(shipmentService, times(1)).createShipment(fixture.sellerOrderId(), List.of("식물1"));
    }

    @Test
    @DisplayName("거절 환불이 결과 불명이어도 복구가 같은 키로 다시 요청해 끝내고, 판매 재개 사유는 SELLER, 배송비도 다시 요청한다")
    void recoveredRejectRefundKeepsSellerReason() {
        Fixture fixture = pendingOrder(1);
        Long itemId = fixture.itemIds().getFirst();
        when(paymentUseCase.refundCancelledItem(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(RefundOutcome.unknown("TIMEOUT"), RefundOutcome.refunded());
        when(paymentUseCase.refundShippingFee(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(RefundOutcome.unknown("TIMEOUT"), RefundOutcome.refunded());

        reject(fixture, List.of(itemId));
        assertThat(item(itemId)).containsEntry("status", "CANCEL_PROCESSING");
        verify(outboxPublisher, never()).publish(any());

        passGrace(itemId);
        recoveryService.recoverProcessingRefunds();

        assertThat(item(itemId)).containsEntry("status", "CANCELLED").containsEntry("cancel_reason", "SELLER");
        verify(paymentUseCase, times(2)).refundShippingFee(eq(fixture.sellerOrderId()), eq(fixture.orderId()),
                eq(BUYER_ID), argEquals(SHIPPING_FEE));
        verify(outboxPublisher, times(1)).publish(new OrderItemCancelled(fixture.orderId(), itemId, 101L,
                CancelReason.SELLER));
    }

    @Test
    @DisplayName("이 판매자 주문에 없는 상품을 거절하면 400, 남의 판매자 주문이면 404이고 아무것도 바뀌지 않는다")
    void rejectsInvalidRequests() {
        Fixture fixture = pendingOrder(1);
        Fixture other = pendingOrder(1);

        assertThatThrownBy(() -> decisions.reject(SELLER_ID, fixture.sellerOrderId(), other.itemIds()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.INVALID_REJECT_ITEMS);
        assertThatThrownBy(() -> decisions.accept(SELLER_ID + 1, fixture.sellerOrderId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.SELLER_ORDER_NOT_FOUND);
        assertThat(itemStatuses(fixture)).containsOnly("ACCEPT_PENDING");
    }

    @Test
    @DisplayName("수락과 거절이 동시에 와도 하나만 반영되고, 늦게 온 쪽은 409")
    void concurrentAcceptAndRejectApplyOnce() throws Exception {
        Fixture fixture = pendingOrder(2);
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());

        List<Throwable> failures = runConcurrently(
                () -> {
                    decisions.accept(SELLER_ID, fixture.sellerOrderId());
                    return null;
                },
                () -> {
                    reject(fixture, List.of(fixture.itemIds().getFirst()));
                    return null;
                });

        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst()).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.SELLER_ORDER_NOT_ACCEPTABLE);
        List<String> statuses = itemStatuses(fixture);
        // 수락이 먼저면 둘 다 NORMAL, 거절이 먼저면 하나 CANCELLED + 하나 NORMAL
        assertThat(statuses).doesNotContain("ACCEPT_PENDING");
        assertThat(statuses.stream().filter("CANCELLED"::equals).count()).isLessThanOrEqualTo(1);
        verify(shipmentService, times(1)).createShipment(any(), any());
    }

    @Test
    @DisplayName("구매자가 전체 취소한 뒤 온 수락, 거절은 409")
    void acceptAfterBuyerCancelIsConflict() {
        Fixture fixture = pendingOrder(2);
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());

        buyerCancel(fixture.orderId());

        assertNotAcceptable(() -> decisions.accept(SELLER_ID, fixture.sellerOrderId()));
        assertNotAcceptable(() -> decisions.reject(SELLER_ID, fixture.sellerOrderId(), fixture.itemIds()));
    }

    @Test
    @DisplayName("수락 전 구매자 전체 취소: 배송 호출 없이 바로 상품 금액과 배송비를 환불하고 판매 재개 BUYER")
    void buyerCancelBeforeAcceptRefundsWithoutCourier() {
        Fixture fixture = pendingOrder(2);
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());

        buyerCancel(fixture.orderId());

        assertThat(itemStatuses(fixture)).containsOnly("CANCELLED");
        verify(shipmentService, never()).cancelShipment(any());
        verify(shipmentService, never()).createShipment(any(), any());
        verify(paymentUseCase, times(1)).refundShippingFee(eq(fixture.sellerOrderId()), eq(fixture.orderId()),
                eq(BUYER_ID), argEquals(SHIPPING_FEE));
        verify(outboxPublisher, times(2)).publish(any(OrderItemCancelled.class));
        verify(sellerOrderEventPublisher, times(1)).publishAfterCommit(any(SellerOrderCancelledEvent.class));
        assertThat(orderStatus(fixture.orderId())).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("수락 전 상품 하나 취소: 배송 호출 없이 그 상품만 환불, 마지막 상품을 취소하면 배송비도 환불")
    void buyerItemCancelBeforeAccept() {
        Fixture fixture = pendingOrder(2);
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());

        itemCancel(fixture.itemIds().getFirst());
        verify(paymentUseCase, never()).refundShippingFee(any(), any(), any(), any());
        assertThat(item(fixture.itemIds().get(1))).containsEntry("status", "ACCEPT_PENDING");

        itemCancel(fixture.itemIds().get(1));

        assertThat(itemStatuses(fixture)).containsOnly("CANCELLED");
        verify(paymentUseCase, times(1)).refundShippingFee(eq(fixture.sellerOrderId()), eq(fixture.orderId()),
                eq(BUYER_ID), argEquals(SHIPPING_FEE));
        verify(shipmentService, never()).cancelShipment(any());
        verify(outboxPublisher).publish(new OrderItemCancelled(fixture.orderId(), fixture.itemIds().getFirst(), 101L,
                CancelReason.BUYER));
    }

    @Test
    @DisplayName("수락 뒤 구매자 취소는 지금처럼 배송을 취소 대기로 두고 택배 결과를 기다린다 (환불 안 함)")
    void buyerCancelAfterAcceptWaitsForCourier() {
        Fixture fixture = pendingOrder(1);
        decisions.accept(SELLER_ID, fixture.sellerOrderId());
        insertShipment(fixture.sellerOrderId(), "READY"); // ShipmentService가 목이라 수락이 만들 배송 행을 직접 넣는다

        buyerCancel(fixture.orderId());

        assertThat(item(fixture.itemIds().getFirst())).containsEntry("status", "CANCEL_PROCESSING")
                .containsEntry("attempted_at", null).containsEntry("cancel_reason", "BUYER");
        verify(shipmentService).cancelShipment(fixture.sellerOrderId());
        verify(paymentUseCase, never()).refundCancelledItem(any(), any(), any(), any());
    }

    @Test
    @DisplayName("[S-D09] 자동 거절은 수락 마감(결제 완료 후 24시간)이 지난 판매자 주문만 거절한다. 23시간 59분, 결제 완료 시각 없음, 이미 수락된 판매자 주문은 그대로")
    void autoRejectsOnlyExpiredSellerOrders() {
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());
        Fixture expired = pendingOrder(1);
        Fixture almost = pendingOrder(1);
        Fixture noPaidAt = pendingOrder(1);
        Fixture accepted = pendingOrder(1);
        decisions.accept(SELLER_ID, accepted.sellerOrderId());
        paidAgo(expired, 24 * 60);
        paidAgo(almost, 24 * 60 - 1);
        jdbcTemplate.update("update seller_order set paid_at = null where id = ?", noPaidAt.sellerOrderId());
        paidAgo(accepted, 25 * 60);

        orderService.autoRejectExpiredSellerOrders();

        assertThat(itemStatuses(expired)).containsOnly("CANCELLED");
        assertThat(itemStatuses(almost)).containsOnly("ACCEPT_PENDING");
        assertThat(itemStatuses(noPaidAt)).containsOnly("ACCEPT_PENDING");
        assertThat(itemStatuses(accepted)).containsOnly("NORMAL");
        verify(paymentUseCase, times(1)).refundCancelledItem(any(), any(), any(), any());
    }

    @Test
    @DisplayName("[S-D09] 자동 거절: 남은 상품이 취소 중으로 커밋된 뒤 환불되고 CANCELLED, 취소 사유와 판매 재개 사유는 AUTO, 배송비 환불, 배송 없음, 판매자에게 구매자 취소 알림 없음")
    void autoRejectRefundsAllRemainingItems() {
        Fixture fixture = pendingOrder(2);
        paidAgo(fixture, 25 * 60);
        List<String> statusesAtRefund = new ArrayList<>();
        when(paymentUseCase.refundCancelledItem(anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            statusesAtRefund.add(item(invocation.getArgument(0)).get("status").toString());
            return RefundOutcome.refunded();
        });
        when(paymentUseCase.refundShippingFee(anyLong(), anyLong(), anyLong(), any())).thenReturn(RefundOutcome.refunded());

        orderService.autoRejectExpiredSellerOrders();

        assertThat(statusesAtRefund).containsExactly("CANCEL_PROCESSING", "CANCEL_PROCESSING");
        for (Long itemId : fixture.itemIds()) {
            assertThat(item(itemId)).containsEntry("status", "CANCELLED").containsEntry("cancel_reason", "AUTO");
        }
        verify(outboxPublisher).publish(new OrderItemCancelled(fixture.orderId(), fixture.itemIds().get(0), 101L,
                CancelReason.AUTO));
        verify(outboxPublisher).publish(new OrderItemCancelled(fixture.orderId(), fixture.itemIds().get(1), 102L,
                CancelReason.AUTO));
        verify(paymentUseCase, times(1)).refundShippingFee(eq(fixture.sellerOrderId()), eq(fixture.orderId()),
                eq(BUYER_ID), argEquals(SHIPPING_FEE));
        verify(shipmentService, never()).createShipment(any(), any());
        verify(sellerOrderEventPublisher, never()).publishAfterCommit(any(OrderItemCancelledEvent.class));
        verify(sellerOrderEventPublisher, never()).publishAfterCommit(any(SellerOrderCancelledEvent.class));
        assertThat(orderStatus(fixture.orderId())).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("[S-D09] 구매자가 상품 하나를 미리 취소한 판매자 주문은 남은 상품만 자동 거절되고 배송비는 한 번만 환불 요청된다")
    void autoRejectAfterBuyerItemCancelRefundsShippingFeeOnce() {
        Fixture fixture = pendingOrder(2);
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());
        itemCancel(fixture.itemIds().getFirst());
        paidAgo(fixture, 25 * 60);

        orderService.autoRejectExpiredSellerOrders();

        assertThat(item(fixture.itemIds().get(0))).containsEntry("status", "CANCELLED").containsEntry("cancel_reason", "BUYER");
        assertThat(item(fixture.itemIds().get(1))).containsEntry("status", "CANCELLED").containsEntry("cancel_reason", "AUTO");
        verify(paymentUseCase, times(2)).refundCancelledItem(any(), any(), any(), any());
        verify(paymentUseCase, times(1)).refundShippingFee(eq(fixture.sellerOrderId()), eq(fixture.orderId()),
                eq(BUYER_ID), argEquals(SHIPPING_FEE));
    }

    @Test
    @DisplayName("[S-D09] 자동 거절 환불이 결과 불명이면 취소 중으로 남고, Saga 복구가 같은 키로 다시 요청해 AUTO 사유로 끝낸다. 다음 주기 자동 거절은 다시 집지 않는다")
    void recoveredAutoRejectRefundKeepsAutoReason() {
        Fixture fixture = pendingOrder(1);
        Long itemId = fixture.itemIds().getFirst();
        paidAgo(fixture, 25 * 60);
        when(paymentUseCase.refundCancelledItem(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(RefundOutcome.unknown("TIMEOUT"), RefundOutcome.refunded());
        when(paymentUseCase.refundShippingFee(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(RefundOutcome.unknown("TIMEOUT"), RefundOutcome.refunded());

        orderService.autoRejectExpiredSellerOrders();
        assertThat(item(itemId)).containsEntry("status", "CANCEL_PROCESSING").containsEntry("cancel_reason", "AUTO");
        verify(outboxPublisher, never()).publish(any());

        orderService.autoRejectExpiredSellerOrders(); // 수락 대기가 없어 후보가 아니다
        verify(paymentUseCase, times(1)).refundCancelledItem(any(), any(), any(), any());

        passGrace(itemId);
        recoveryService.recoverProcessingRefunds();

        assertThat(item(itemId)).containsEntry("status", "CANCELLED").containsEntry("cancel_reason", "AUTO");
        verify(paymentUseCase, times(2)).refundShippingFee(eq(fixture.sellerOrderId()), eq(fixture.orderId()),
                eq(BUYER_ID), argEquals(SHIPPING_FEE));
        verify(outboxPublisher, times(1)).publish(new OrderItemCancelled(fixture.orderId(), itemId, 101L,
                CancelReason.AUTO));
    }

    @Test
    @DisplayName("자동 거절이 먼저 처리한 판매자 주문에 온 수락, 거절은 409이고 배송을 만들지 않는다")
    void acceptAfterAutoRejectIsConflict() {
        Fixture fixture = pendingOrder(1);
        paidAgo(fixture, 25 * 60);
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());

        orderService.autoRejectExpiredSellerOrders();

        assertNotAcceptable(() -> decisions.accept(SELLER_ID, fixture.sellerOrderId()));
        assertNotAcceptable(() -> decisions.reject(SELLER_ID, fixture.sellerOrderId(), fixture.itemIds()));
        verify(shipmentService, never()).createShipment(any(), any());
    }

    @Test
    @DisplayName("수락, 거절, 구매자 취소가 주문을 잡고 있으면 자동 거절은 기다리지 않고 건너뛰어 다음 주기에 다시 본다")
    void autoRejectSkipsLockedOrderWithoutWaiting() {
        Fixture fixture = pendingOrder(1);
        paidAgo(fixture, 25 * 60);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.queryForObject("select id from orders where id = ? for update", Long.class, fixture.orderId());
            assertThatCode(() -> CompletableFuture.supplyAsync(() -> decisions.autoReject(fixture.sellerOrderId()))
                    .get(2, TimeUnit.SECONDS)).doesNotThrowAnyException();
        });

        assertThat(itemStatuses(fixture)).containsOnly("ACCEPT_PENDING");
        verify(paymentUseCase, never()).refundCancelledItem(any(), any(), any(), any());
    }

    @Test
    @DisplayName("자동 거절과 판매자 수락이 동시에 와도 하나만 반영되고, 배송 생성과 환불이 섞이지 않는다")
    void concurrentAutoRejectAndAcceptApplyOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            Fixture fixture = pendingOrder(2);
            paidAgo(fixture, 25 * 60);
            org.mockito.Mockito.clearInvocations(paymentUseCase, shipmentService);
            mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());

            List<Throwable> failures = runConcurrently(
                    () -> {
                        orderService.autoRejectExpiredSellerOrders();
                        return null;
                    },
                    () -> {
                        decisions.accept(SELLER_ID, fixture.sellerOrderId());
                        return null;
                    });

            List<String> statuses = itemStatuses(fixture);
            if (failures.isEmpty()) {
                // 수락이 먼저 주문을 잡음: 자동 거절은 건너뛰거나 다시 확인에서 빠진다
                assertThat(statuses).containsOnly("NORMAL");
                verify(shipmentService, times(1)).createShipment(any(), any());
                verify(paymentUseCase, never()).refundCancelledItem(any(), any(), any(), any());
                verify(paymentUseCase, never()).refundShippingFee(any(), any(), any(), any());
            } else {
                // 자동 거절이 먼저 주문을 잡음: 늦게 온 수락은 409
                assertThat(failures).singleElement().isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getErrorCode())
                        .isEqualTo(OrderErrorCode.SELLER_ORDER_NOT_ACCEPTABLE);
                assertThat(statuses).containsOnly("CANCELLED");
                verify(shipmentService, never()).createShipment(any(), any());
                verify(paymentUseCase, times(2)).refundCancelledItem(any(), any(), any(), any());
                verify(paymentUseCase, times(1)).refundShippingFee(any(), any(), any(), any());
            }
        }
    }

    // ===== 수락, 거절 이벤트 (#288). 판매자 주문당 결정 시점에 정확히 1건, 환불이 끝난 뒤에도 늘지 않는다

    @Test
    @DisplayName("전체 수락: SellerOrderAccepted 1건(rejectedItemCount 0, 주문, 판매자 주문, 판매자, 구매자 ID 일치), 거절 이벤트 없음")
    void acceptPublishesAcceptedOnce() {
        Fixture fixture = pendingOrder(2);

        decisions.accept(SELLER_ID, fixture.sellerOrderId());

        assertThat(acceptedEvents(fixture)).singleElement().satisfies(event -> {
            assertThat(event).isEqualTo(new SellerOrderAcceptedEvent(fixture.orderId(), fixture.sellerOrderId(),
                    SELLER_ID, BUYER_ID, 0, event.acceptedAt()));
            assertThat(event.acceptedAt()).isNotNull();
        });
        assertThat(rejectedEvents(fixture)).isEmpty();
    }

    @Test
    @DisplayName("일부 거절(3개 중 1개): 환불까지 끝난 뒤에도 SellerOrderAccepted 1건(rejectedItemCount 1), SellerOrderRejected 없음")
    void partialRejectPublishesAcceptedWithRejectedCount() {
        Fixture fixture = pendingOrder(3);
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());

        reject(fixture, List.of(fixture.itemIds().getFirst()));

        assertThat(item(fixture.itemIds().getFirst())).containsEntry("status", "CANCELLED");
        assertThat(acceptedEvents(fixture)).singleElement().isEqualTo(new SellerOrderAcceptedEvent(fixture.orderId(),
                fixture.sellerOrderId(), SELLER_ID, BUYER_ID, 1, acceptedEvents(fixture).getFirst().acceptedAt()));
        assertThat(rejectedEvents(fixture)).isEmpty();
    }

    @Test
    @DisplayName("전부 거절: 환불까지 끝난 뒤에도 SellerOrderRejected(SELLER) 1건, SellerOrderAccepted와 구매자 취소 알림 없음")
    void rejectAllPublishesRejectedBySeller() {
        Fixture fixture = pendingOrder(2);
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());

        reject(fixture, fixture.itemIds());

        assertThat(itemStatuses(fixture)).containsOnly("CANCELLED");
        assertRejectedOnce(fixture, SellerOrderRejectReason.SELLER);
        assertThat(acceptedEvents(fixture)).isEmpty();
        verify(sellerOrderEventPublisher, never()).publishAfterCommit(any(SellerOrderCancelledEvent.class));
        verify(sellerOrderEventPublisher, never()).publishAfterCommit(any(OrderItemCancelledEvent.class));
    }

    @Test
    @DisplayName("구매자가 1개를 먼저 취소한 뒤 남은 상품을 전부 거절하면 SellerOrderRejected(SELLER) 1건, 구매자 판매자 주문 전부 취소 알림 없음")
    void rejectRemainingAfterBuyerItemCancelPublishesRejected() {
        Fixture fixture = pendingOrder(3);
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());
        itemCancel(fixture.itemIds().getFirst());

        reject(fixture, fixture.itemIds().subList(1, 3));

        assertThat(itemStatuses(fixture)).containsOnly("CANCELLED");
        assertRejectedOnce(fixture, SellerOrderRejectReason.SELLER);
        assertThat(acceptedEvents(fixture)).isEmpty();
        verify(sellerOrderEventPublisher, never()).publishAfterCommit(any(SellerOrderCancelledEvent.class));
    }

    @Test
    @DisplayName("[S-D09] 자동 거절: 환불까지 끝난 뒤 SellerOrderRejected(AUTO) 1건, 다시 돌려도 늘지 않는다")
    void autoRejectPublishesRejectedAutoOnce() {
        Fixture fixture = pendingOrder(2);
        paidAgo(fixture, 25 * 60);
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());

        orderService.autoRejectExpiredSellerOrders();
        orderService.autoRejectExpiredSellerOrders();
        assertThat(decisions.autoReject(fixture.sellerOrderId())).isEmpty(); // 이미 처리된 건을 직접 다시 불러도 빈 목록

        assertThat(itemStatuses(fixture)).containsOnly("CANCELLED");
        assertRejectedOnce(fixture, SellerOrderRejectReason.AUTO);
        assertThat(acceptedEvents(fixture)).isEmpty();
    }

    @Test
    @DisplayName("이미 수락된 판매자 주문에 늦게 온 거절은 409, 자동 거절 재실행은 건너뛰고 수락 이벤트 1건만 남는다")
    void lateDecisionsPublishNothing() {
        Fixture fixture = pendingOrder(2);
        decisions.accept(SELLER_ID, fixture.sellerOrderId());
        paidAgo(fixture, 25 * 60);

        assertNotAcceptable(() -> decisions.reject(SELLER_ID, fixture.sellerOrderId(), fixture.itemIds()));
        assertNotAcceptable(() -> decisions.accept(SELLER_ID, fixture.sellerOrderId()));
        assertThat(decisions.autoReject(fixture.sellerOrderId())).isEmpty();
        orderService.autoRejectExpiredSellerOrders();

        assertThat(acceptedEvents(fixture)).hasSize(1);
        assertThat(rejectedEvents(fixture)).isEmpty();
    }

    @Test
    @DisplayName("자동 거절이 먼저 처리한 판매자 주문에 늦게 온 수락, 거절은 409이고 거절 이벤트 1건만 남는다")
    void lateDecisionsAfterAutoRejectPublishNothing() {
        Fixture fixture = pendingOrder(1);
        paidAgo(fixture, 25 * 60);
        mockRefunds(RefundOutcome.refunded(), RefundOutcome.refunded());
        orderService.autoRejectExpiredSellerOrders();

        assertNotAcceptable(() -> decisions.accept(SELLER_ID, fixture.sellerOrderId()));
        assertNotAcceptable(() -> decisions.reject(SELLER_ID, fixture.sellerOrderId(), fixture.itemIds()));

        assertRejectedOnce(fixture, SellerOrderRejectReason.AUTO);
        assertThat(acceptedEvents(fixture)).isEmpty();
    }

    // ===== helpers

    // OrderService.rejectSellerOrderItems와 같은 순서: 거절 커밋 → (트랜잭션 밖) 판매자 주문별 환불
    private void reject(Fixture fixture, List<Long> itemIds) {
        refundBySellerOrder(decisions.reject(SELLER_ID, fixture.sellerOrderId(), itemIds));
    }

    // OrderService.cancel과 같은 순서
    private void buyerCancel(Long orderId) {
        refundBySellerOrder(cancelTransactions.requestCancel(BUYER_ID, orderId));
    }

    private void itemCancel(Long itemId) {
        cancelTransactions.requestItemCancel(BUYER_ID, itemId).ifPresent(claim -> refunder.refund(List.of(claim)));
    }

    private void refundBySellerOrder(List<RefundClaim> claims) {
        claims.stream()
                .collect(Collectors.groupingBy(RefundClaim::sellerOrderId, LinkedHashMap::new, Collectors.toList()))
                .values()
                .forEach(refunder::refund);
    }

    private static void assertNotAcceptable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.SELLER_ORDER_NOT_ACCEPTABLE);
    }

    @SafeVarargs
    private static List<Throwable> runConcurrently(Callable<Void>... tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
            return failures;
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertRejectedOnce(Fixture fixture, SellerOrderRejectReason reason) {
        assertThat(rejectedEvents(fixture)).singleElement().satisfies(event -> {
            assertThat(event).isEqualTo(new SellerOrderRejectedEvent(fixture.orderId(), fixture.sellerOrderId(),
                    SELLER_ID, BUYER_ID, reason, event.rejectedAt()));
            assertThat(event.rejectedAt()).isNotNull();
        });
    }

    // SellerOrderEventPublisher 목에 넘어간 이벤트 중 이 판매자 주문의 것 (Outbox에 남을 행)
    private List<SellerOrderAcceptedEvent> acceptedEvents(Fixture fixture) {
        return publishedEvents(SellerOrderAcceptedEvent.class).stream()
                .filter(event -> event.sellerOrderId().equals(fixture.sellerOrderId())).toList();
    }

    private List<SellerOrderRejectedEvent> rejectedEvents(Fixture fixture) {
        return publishedEvents(SellerOrderRejectedEvent.class).stream()
                .filter(event -> event.sellerOrderId().equals(fixture.sellerOrderId())).toList();
    }

    private <T> List<T> publishedEvents(Class<T> type) {
        return org.mockito.Mockito.mockingDetails(sellerOrderEventPublisher).getInvocations().stream()
                .flatMap(invocation -> java.util.Arrays.stream(invocation.getArguments()))
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private void mockRefunds(RefundOutcome item, RefundOutcome shippingFee) {
        when(paymentUseCase.refundCancelledItem(anyLong(), anyLong(), anyLong(), any())).thenReturn(item);
        when(paymentUseCase.refundShippingFee(anyLong(), anyLong(), anyLong(), any())).thenReturn(shippingFee);
    }

    private static BigDecimal argEquals(BigDecimal expected) {
        return org.mockito.ArgumentMatchers.argThat(actual -> actual != null && actual.compareTo(expected) == 0);
    }

    // 결제된 주문 1건, 판매자 주문 1건, 수락 대기 주문상품 n건(상품 101, 102 …, 각 10,000원), 배송비 3,000원, 배송 없음
    private Fixture pendingOrder(int itemCount) {
        Orders order = new Orders("ORD-sd-" + System.nanoTime(), BUYER_ID, "구매자", "01012345678", "12345", "서울시",
                null, itemCount);
        SellerOrder sellerOrder = new SellerOrder(order, SELLER_ID);
        sellerOrder.addItems(IntStream.range(0, itemCount)
                .mapToObj(i -> new OrdersItem(sellerOrder, 101L + i, "식물" + i, PRICE)).toList());
        sellerOrder.setShippingFee(SHIPPING_FEE);
        order.addSellerOrders(List.of(sellerOrder));
        Long orderId = paymentTransactions.saveNewOrder(order).getId();
        jdbcTemplate.update("update orders set status = 'PAID' where id = ?", orderId);
        Long sellerOrderId = jdbcTemplate.queryForObject("select id from seller_order where order_id = ?", Long.class,
                orderId);
        jdbcTemplate.update("update seller_order set paid_at = now() where id = ?", sellerOrderId);
        jdbcTemplate.update("update orders_item set status = 'ACCEPT_PENDING' where seller_order_id = ?", sellerOrderId);
        List<Long> itemIds = jdbcTemplate.queryForList(
                "select id from orders_item where seller_order_id = ? order by id", Long.class, sellerOrderId);
        return new Fixture(orderId, sellerOrderId, itemIds);
    }

    // 결제 완료 시각을 지금(앱 시각)에서 minutes분 전으로 맞춘다
    private void paidAgo(Fixture fixture, long minutes) {
        jdbcTemplate.update("update seller_order set paid_at = ? where id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(minutes)), fixture.sellerOrderId());
    }

    private void insertShipment(Long sellerOrderId, String status) {
        jdbcTemplate.update("""
                insert into shipment (seller_order_id, status, version, created_at, updated_at)
                values (?, ?, 0, now(), now())""", sellerOrderId, status);
    }

    private void passGrace(Long itemId) {
        jdbcTemplate.update("update orders_item set attempted_at = ? where id = ? and attempted_at is not null",
                Timestamp.valueOf(LocalDateTime.now().minusSeconds(20)), itemId);
    }

    private Map<String, Object> item(Long itemId) {
        return jdbcTemplate.queryForMap(
                "select status, attempted_at, cancel_reason from orders_item where id = ?", itemId);
    }

    private List<String> itemStatuses(Fixture fixture) {
        return jdbcTemplate.queryForList("select status from orders_item where seller_order_id = ? order by id",
                String.class, fixture.sellerOrderId());
    }

    private String orderStatus(Long orderId) {
        return jdbcTemplate.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private BigDecimal settlementBaseAmount(Long sellerOrderId) {
        return jdbcTemplate.queryForObject("select settlement_base_amount from seller_order where id = ?",
                BigDecimal.class, sellerOrderId);
    }

    private record Fixture(Long orderId, Long sellerOrderId, List<Long> itemIds) {
    }
}
