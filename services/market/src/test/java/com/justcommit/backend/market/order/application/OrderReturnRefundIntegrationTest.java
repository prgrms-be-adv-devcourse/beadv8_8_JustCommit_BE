package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.outbox.OutboxAutoConfiguration;
import com.justcommit.backend.common.messaging.outbox.OutboxRelay;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.market.OrderItemReturnedEvent;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.PurchaseConfirmedEvent;
import com.justcommit.backend.market.ReturnDecidedBy;
import com.justcommit.backend.market.ReturnReason;
import com.justcommit.backend.market.SellerOrderReturnedEvent;
import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.market.client.payment.PaymentUseCase;
import com.justcommit.backend.market.client.payment.RefundOutcome;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.ReturnRequest;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.event.OrderItemReturned;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.ReturnRequestRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.PurchaseConfirmedEventPublisher;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import com.justcommit.backend.market.shipment.application.ShipmentQueryService;
import com.justcommit.backend.market.shipment.application.ShipmentService;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 반품 도착 처리와 반품 환불 (#384). 실제 DB 잠금, 실제 반품 배송 행과 배송 조회, 실제 OutboxPublisher로 outbox_event 행을 확인한다.
// payment만 목이며, 같은 키(orderItemId, sellerOrderId)의 두 번째 요청은 실제 환불 없이 처음 결과를 돌려주는 것처럼 센다
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, OrderPaymentTransactions.class, OrderRecoveryService.class,
        OrderReturnRefundTransactions.class, OrderReturnRefunder.class, OrderCancelTransactions.class,
        OrderCancelRefunder.class, ShipmentQueryService.class, SellerOrderCompletion.class,
        SellerOrderEventPublisher.class, PurchaseConfirmedEventPublisher.class, EventEnvelopeMapper.class})
@ImportAutoConfiguration(OutboxAutoConfiguration.class)
@TestPropertySource(properties = "outbox.scheduler.enabled=false")
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 도착 처리, 선점, 반영이 각자 트랜잭션을 열고 커밋한다
class OrderReturnRefundIntegrationTest {

    private static final long BUYER_ID = 10L;
    private static final long SELLER_ID = 20L;
    private static final BigDecimal SHIPPING_FEE = new BigDecimal("3000");
    private static final BigDecimal RETURN_SHIPPING_FEE = new BigDecimal("3000");
    private static final EventEnvelopeMapper ENVELOPE_MAPPER = new EventEnvelopeMapper();

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @MockitoBean PaymentUseCase paymentUseCase;
    @MockitoBean ShipmentService shipmentService; // 취소 쪽 빈이 의존. 반품 도착, 환불은 부르지 않는다
    @MockitoBean OrderQuery orderQuery;
    @MockitoBean CartService cartService;
    @MockitoBean OutboxRelay outboxRelay;

    @Autowired OrderRecoveryService recoveryService;
    @Autowired OrdersRepository ordersRepository;
    @Autowired ReturnRequestRepository returnRequestRepository;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    // payment 쪽에서 실제로 환불된 키. 같은 키의 두 번째 요청은 ALREADY_REFUNDED라 여기에 다시 들어가지 않는다
    private final Set<Long> refundedItems = ConcurrentHashMap.newKeySet();
    private final Set<Long> refundedShippingFees = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void idempotentPayment() {
        when(paymentUseCase.refundCancelledItem(anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            refundedItems.add(invocation.getArgument(0));
            return RefundOutcome.refunded();
        });
        when(paymentUseCase.refundShippingFee(anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            refundedShippingFees.add(invocation.getArgument(0));
            return RefundOutcome.refunded();
        });
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("truncate table outbox_event, return_request, shipment, orders_item, seller_order, orders");
    }

    @Test
    @DisplayName("[S-E06] 반품 도착 → 반품 환불 중(환불 안 부름) → 환불 → 반품 완료, 반품 신청 완료, 판매 재개와 환불 완료 이벤트")
    void arrivalThenRefundCompletesReturn() {
        Fixture fixture = sellerOrder(List.of(OrderItemStatus.RETURN_IN_TRANSIT, OrderItemStatus.CONFIRMED));
        Long itemId = fixture.itemIds().getFirst();

        recoveryService.recoverReturnArrivals();

        assertThat(item(itemId)).containsEntry("status", "RETURN_PROCESSING").containsEntry("attempted_at", null);
        verify(paymentUseCase, never()).refundCancelledItem(anyLong(), anyLong(), anyLong(), any());

        recoveryService.recoverReturnRefunds();

        verify(paymentUseCase).refundCancelledItem(itemId, fixture.orderId(), BUYER_ID, new BigDecimal("10000"));
        verify(paymentUseCase, never()).refundShippingFee(anyLong(), anyLong(), anyLong(), any());
        assertThat(item(itemId)).containsEntry("status", "RETURNED");
        assertThat(returnRequestStatus(itemId)).isEqualTo("COMPLETED");

        List<OrderItemReturned> resumed = productEvents(fixture);
        assertThat(resumed).containsExactly(new OrderItemReturned(fixture.orderId(), itemId, 100L));
        assertThat(marketEvents("OrderItemReturned", fixture, OrderItemReturnedEvent.class)).singleElement()
                .satisfies(event -> {
                    assertThat(event.returnRequestId()).isEqualTo(fixture.returnRequestIds().get(itemId));
                    assertThat(event.orderItemId()).isEqualTo(itemId);
                    assertThat(event.orderId()).isEqualTo(fixture.orderId());
                    assertThat(event.buyerId()).isEqualTo(BUYER_ID);
                    assertThat(event.sellerId()).isEqualTo(SELLER_ID);
                    assertThat(event.productName()).isEqualTo("상품0");
                    assertThat(event.refundAmount()).isEqualByComparingTo("10000");
                    assertThat(event.returnedAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("반품 배송이 아직 도착 전이면 도착 처리도 환불도 하지 않는다")
    void notDeliveredYet() {
        Fixture fixture = sellerOrder(List.of(OrderItemStatus.RETURN_IN_TRANSIT));
        Long itemId = fixture.itemIds().getFirst();
        jdbcTemplate.update("update shipment set status = 'IN_TRANSIT', delivered_at = null");

        recoveryService.recoverReturnArrivals();
        recoveryService.recoverReturnRefunds();

        assertThat(item(itemId)).containsEntry("status", "RETURN_IN_TRANSIT");
        verify(paymentUseCase, never()).refundCancelledItem(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("일부 반품: 배송비 환불 없음, 판매자 주문이 끝나면 PurchaseConfirmed에 반품 배송비 부담금")
    void partialReturnHasNoShippingFeeRefund() {
        Fixture fixture = sellerOrder(List.of(OrderItemStatus.RETURN_IN_TRANSIT, OrderItemStatus.CONFIRMED));

        recoveryService.recoverReturnArrivals();
        recoveryService.recoverReturnRefunds();

        verify(paymentUseCase, never()).refundShippingFee(anyLong(), anyLong(), anyLong(), any());
        assertThat(marketEvents("SellerOrderReturned", fixture, SellerOrderReturnedEvent.class)).isEmpty();
        assertThat(marketEvents("PurchaseConfirmed", fixture, PurchaseConfirmedEvent.class)).singleElement()
                .satisfies(event -> {
                    assertThat(event.saleAmount()).isEqualByComparingTo("20000");
                    assertThat(event.sellerBurdenAmount()).isEqualByComparingTo(RETURN_SHIPPING_FEE);
                });
    }

    @Test
    @DisplayName("환불 결과 불명 → 반품 환불 중 유지 → 웹훅 중복처럼 도착 처리를 다시 돌리고 복구가 겹쳐도 같은 키로 1번 반영")
    void unknownOutcomeRetriesWithSameKeyAndAppliesOnce() throws Exception {
        Fixture fixture = sellerOrder(List.of(OrderItemStatus.RETURN_IN_TRANSIT, OrderItemStatus.CONFIRMED));
        Long itemId = fixture.itemIds().getFirst();
        when(paymentUseCase.refundCancelledItem(eq(itemId), anyLong(), anyLong(), any()))
                .thenReturn(RefundOutcome.unknown("TIMEOUT"))
                .thenAnswer(invocation -> {
                    refundedItems.add(itemId);
                    return RefundOutcome.refunded();
                });

        recoveryService.recoverReturnArrivals();
        recoveryService.recoverReturnArrivals(); // 같은 도착을 다시 봐도 이미 환불 중이라 건너뜀
        recoveryService.recoverReturnRefunds();

        assertThat(item(itemId)).containsEntry("status", "RETURN_PROCESSING").containsEntry("last_error", "TIMEOUT")
                .containsEntry("attempt_count", 0);
        assertThat(productEvents(fixture)).isEmpty();

        // 유예 전에는 다시 부르지 않는다
        recoveryService.recoverReturnRefunds();
        verify(paymentUseCase, times(1)).refundCancelledItem(eq(itemId), anyLong(), anyLong(), any());

        passGrace();
        runConcurrently(() -> {
            recoveryService.recoverReturnArrivals();
            recoveryService.recoverReturnRefunds();
        });
        recoveryService.recoverReturnRefunds();

        verify(paymentUseCase, times(2)).refundCancelledItem(eq(itemId), eq(fixture.orderId()), eq(BUYER_ID),
                eq(new BigDecimal("10000")));
        assertThat(item(itemId)).containsEntry("status", "RETURNED").containsEntry("attempt_count", 1);
        assertThat(productEvents(fixture)).hasSize(1);
        assertThat(marketEvents("OrderItemReturned", fixture, OrderItemReturnedEvent.class)).hasSize(1);
        assertThat(marketEvents("PurchaseConfirmed", fixture, PurchaseConfirmedEvent.class)).hasSize(1);
    }

    @Test
    @DisplayName("전부 반품(차례로 도착): 먼저 끝난 상품은 배송비 없이, 마지막 상품이 배송비 환불 1번 + SellerOrderReturned 1번")
    void wholeReturnArrivingOneByOneRefundsShippingFeeOnce() {
        Fixture fixture = sellerOrder(List.of(OrderItemStatus.RETURN_IN_TRANSIT, OrderItemStatus.RETURN_IN_TRANSIT));
        Long first = fixture.itemIds().get(0);
        Long second = fixture.itemIds().get(1);
        jdbcTemplate.update("update shipment set status = 'IN_TRANSIT', delivered_at = null where id = ?",
                fixture.shipmentIds().get(second));

        recoveryService.recoverReturnArrivals();
        recoveryService.recoverReturnRefunds();

        assertThat(item(first)).containsEntry("status", "RETURNED");
        verify(paymentUseCase, never()).refundShippingFee(anyLong(), anyLong(), anyLong(), any());
        assertThat(marketEvents("SellerOrderReturned", fixture, SellerOrderReturnedEvent.class)).isEmpty();

        jdbcTemplate.update("update shipment set status = 'DELIVERED', delivered_at = now() where id = ?",
                fixture.shipmentIds().get(second));
        recoveryService.recoverReturnArrivals();
        recoveryService.recoverReturnRefunds();

        assertThat(item(second)).containsEntry("status", "RETURNED");
        verify(paymentUseCase).refundShippingFee(fixture.sellerOrderId(), fixture.orderId(), BUYER_ID, SHIPPING_FEE);
        assertThat(refundedShippingFees).containsExactly(fixture.sellerOrderId());
        assertWholeReturnSettledOnce(fixture);
    }

    @Test
    @DisplayName("전부 반품(동시 도착, 파드 2개): 배송비는 같은 키라 실제 환불 1번, SellerOrderReturned 1번, 판매 재개 상품마다 1번")
    void wholeReturnArrivingTogetherRefundsShippingFeeOnce() throws Exception {
        Fixture fixture = sellerOrder(List.of(OrderItemStatus.RETURN_IN_TRANSIT, OrderItemStatus.RETURN_IN_TRANSIT));

        runConcurrently(() -> {
            recoveryService.recoverReturnArrivals();
            recoveryService.recoverReturnRefunds();
        });
        recoveryService.recoverReturnRefunds();

        assertThat(fixture.itemIds()).allSatisfy(id -> assertThat(item(id)).containsEntry("status", "RETURNED"));
        // 배송비를 실은 선점이 하나여도 둘이어도 키는 판매자 주문 하나
        verify(paymentUseCase, atLeastOnce())
                .refundShippingFee(fixture.sellerOrderId(), fixture.orderId(), BUYER_ID, SHIPPING_FEE);
        assertThat(refundedShippingFees).containsExactly(fixture.sellerOrderId());
        assertThat(refundedItems).containsExactlyInAnyOrderElementsOf(fixture.itemIds());
        assertThat(productEvents(fixture)).hasSize(2);
        assertWholeReturnSettledOnce(fixture);
    }

    @Test
    @DisplayName("상품 금액 환불 4xx → 다시 요청하지 않고 확인 필요 + last_error, 반품 신청은 승인 상태로 남음")
    void rejectedRefundRequiresReview() {
        Fixture fixture = sellerOrder(List.of(OrderItemStatus.RETURN_IN_TRANSIT, OrderItemStatus.CONFIRMED));
        Long itemId = fixture.itemIds().getFirst();
        when(paymentUseCase.refundCancelledItem(eq(itemId), anyLong(), anyLong(), any()))
                .thenReturn(RefundOutcome.rejected("HTTP_400_INVALID_AMOUNT"));

        recoveryService.recoverReturnArrivals();
        recoveryService.recoverReturnRefunds();
        passGrace();
        recoveryService.recoverReturnRefunds();

        verify(paymentUseCase, times(1)).refundCancelledItem(eq(itemId), anyLong(), anyLong(), any());
        assertThat(item(itemId)).containsEntry("status", "REVIEW_REQUIRED")
                .containsEntry("last_error", "HTTP_400_INVALID_AMOUNT");
        assertThat(returnRequestStatus(itemId)).isEqualTo("APPROVED");
        assertThat(productEvents(fixture)).isEmpty();
        assertThat(marketEvents("PurchaseConfirmed", fixture, PurchaseConfirmedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("전부 반품의 배송비 환불 4xx → 확인 필요, last_error는 SHIPPING_FEE_로 시작")
    void rejectedShippingFeeRefundRequiresReview() {
        Fixture fixture = sellerOrder(List.of(OrderItemStatus.RETURN_IN_TRANSIT));
        Long itemId = fixture.itemIds().getFirst();
        when(paymentUseCase.refundShippingFee(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(RefundOutcome.rejected("HTTP_400_INVALID_AMOUNT"));

        recoveryService.recoverReturnArrivals();
        recoveryService.recoverReturnRefunds();

        assertThat(item(itemId)).containsEntry("status", "REVIEW_REQUIRED")
                .containsEntry("last_error", "SHIPPING_FEE_HTTP_400_INVALID_AMOUNT");
        assertThat(marketEvents("SellerOrderReturned", fixture, SellerOrderReturnedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("결과 불명이 복구 5번 뒤에도 이어지면 확인 필요, 6번째 요청은 하지 않음")
    void unknownAfterFiveRecoveriesRequiresReview() {
        Fixture fixture = sellerOrder(List.of(OrderItemStatus.RETURN_IN_TRANSIT, OrderItemStatus.CONFIRMED));
        Long itemId = fixture.itemIds().getFirst();
        when(paymentUseCase.refundCancelledItem(eq(itemId), anyLong(), anyLong(), any()))
                .thenReturn(RefundOutcome.unknown("TIMEOUT"));

        recoveryService.recoverReturnArrivals();
        recoveryService.recoverReturnRefunds();
        for (int i = 0; i < 6; i++) {
            passGrace();
            recoveryService.recoverReturnRefunds();
        }

        // 첫 호출 1번 + 복구 5번
        verify(paymentUseCase, times(6)).refundCancelledItem(eq(itemId), anyLong(), anyLong(), any());
        assertThat(item(itemId)).containsEntry("status", "REVIEW_REQUIRED").containsEntry("last_error", "TIMEOUT")
                .containsEntry("attempt_count", 5);
        assertThat(productEvents(fixture)).isEmpty();
    }

    private void assertWholeReturnSettledOnce(Fixture fixture) {
        assertThat(returnRequestStatus(fixture.itemIds().get(0))).isEqualTo("COMPLETED");
        assertThat(returnRequestStatus(fixture.itemIds().get(1))).isEqualTo("COMPLETED");
        assertThat(marketEvents("PurchaseConfirmed", fixture, PurchaseConfirmedEvent.class)).isEmpty();
        assertThat(marketEvents("SellerOrderReturned", fixture, SellerOrderReturnedEvent.class)).singleElement()
                .satisfies(event -> assertThat(event.sellerBurdenAmount())
                        .isEqualByComparingTo(RETURN_SHIPPING_FEE.add(RETURN_SHIPPING_FEE).add(SHIPPING_FEE)));
        assertThat(jdbcTemplate.queryForObject("select status from orders where id = ?", String.class,
                fixture.orderId())).isEqualTo("COMPLETED");
    }

    // 파드 2개가 같은 단계를 함께 도는 것처럼 두 스레드를 동시에 출발시킨다
    private void runConcurrently(Runnable step) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    step.run();
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void passGrace() {
        jdbcTemplate.update("update orders_item set attempted_at = attempted_at - interval '11 seconds'"
                + " where attempted_at is not null");
    }

    private Map<String, Object> item(Long itemId) {
        return jdbcTemplate.queryForMap(
                "select status, attempt_count, attempted_at, last_error from orders_item where id = ?", itemId);
    }

    private String returnRequestStatus(Long itemId) {
        return jdbcTemplate.queryForObject("select status from return_request where order_item_id = ?",
                String.class, itemId);
    }

    private List<OrderItemReturned> productEvents(Fixture fixture) {
        return jdbcTemplate.queryForList("""
                        select payload from outbox_event
                        where event_type = 'OrderItemReturned' and topic = 'market.order-item-returned.v1'
                          and aggregate_id = ?
                        order by created_at""", String.class, String.valueOf(fixture.orderId()))
                .stream()
                .map(json -> ENVELOPE_MAPPER.readPayload(ENVELOPE_MAPPER.fromJson(json), OrderItemReturned.class))
                .toList();
    }

    private <T> List<T> marketEvents(String eventType, Fixture fixture, Class<T> type) {
        return jdbcTemplate.queryForList("""
                        select payload from outbox_event
                        where event_type = ? and topic = 'market.events.v1' and aggregate_id = ?
                        order by created_at""", String.class, eventType, String.valueOf(fixture.sellerOrderId()))
                .stream()
                .map(json -> ENVELOPE_MAPPER.readPayload(ENVELOPE_MAPPER.fromJson(json), type))
                .toList();
    }

    // 결제 완료 주문 1건, 판매자 주문 1건(배송비 3,000원), 주문상품 statuses.size()개(상품i, 10,000원 × (i+1)).
    // RETURN_IN_TRANSIT 상품은 승인된 반품 신청(반품 배송비 3,000원)과 도착한(DELIVERED) 반품 배송을 가진다
    private Fixture sellerOrder(List<OrderItemStatus> statuses) {
        Orders order = new Orders("ORD-RR-" + System.nanoTime(), BUYER_ID, "구매자", "01012345678", "12345", "서울시",
                null, statuses.size());
        SellerOrder sellerOrder = new SellerOrder(order, SELLER_ID);
        List<OrdersItem> items = new ArrayList<>();
        for (int i = 0; i < statuses.size(); i++) {
            items.add(new OrdersItem(sellerOrder, 100L + i, "상품" + i, new BigDecimal(10000 * (i + 1))));
        }
        sellerOrder.addItems(items);
        sellerOrder.setShippingFee(SHIPPING_FEE);
        order.addSellerOrders(List.of(sellerOrder));
        order.paid();
        for (int i = 0; i < statuses.size(); i++) {
            ReflectionTestUtils.setField(items.get(i), "status", statuses.get(i));
        }
        ordersRepository.save(order);

        LocalDateTime now = LocalDateTime.now();
        Map<Long, Long> returnRequestIds = new HashMap<>();
        Map<Long, Long> shipmentIds = new HashMap<>();
        for (OrdersItem item : items) {
            if (item.getStatus() != OrderItemStatus.RETURN_IN_TRANSIT) {
                continue;
            }
            Shipment shipment = shipmentRepository.save(Shipment.returnOf(sellerOrder.getId(), item.getProductName()));
            jdbcTemplate.update("update shipment set status = 'DELIVERED', delivered_at = ? where id = ?",
                    now, shipment.getId());
            ReturnRequest returnRequest = new ReturnRequest(item.getId(), sellerOrder.getId(), ReturnReason.DAMAGED,
                    null, now.minusDays(1));
            returnRequest.approve(ReturnDecidedBy.SELLER, now.minusHours(20), RETURN_SHIPPING_FEE, shipment.getId());
            returnRequestIds.put(item.getId(), returnRequestRepository.save(returnRequest).getId());
            shipmentIds.put(item.getId(), shipment.getId());
        }
        return new Fixture(order.getId(), sellerOrder.getId(), items.stream().map(OrdersItem::getId).toList(),
                returnRequestIds, shipmentIds);
    }

    private record Fixture(Long orderId, Long sellerOrderId, List<Long> itemIds, Map<Long, Long> returnRequestIds,
                           Map<Long, Long> shipmentIds) {
    }
}
