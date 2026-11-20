package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.outbox.OutboxAutoConfiguration;
import com.justcommit.backend.common.messaging.outbox.OutboxRelay;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.PurchaseConfirmedEvent;
import com.justcommit.backend.market.ReturnApprovedEvent;
import com.justcommit.backend.market.ReturnDecidedBy;
import com.justcommit.backend.market.ReturnReason;
import com.justcommit.backend.market.ReturnRejectedEvent;
import com.justcommit.backend.market.ShipmentQuery;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.ReturnRequest;
import com.justcommit.backend.market.order.domain.ReturnRequestStatus;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.ReturnRequestRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.PurchaseConfirmedEventPublisher;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import com.justcommit.backend.market.shipment.application.ShipmentService;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.kafka.ShipmentStatusChangedEventPublisher;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

// 판매자 반품 승인, 거절과 48시간 자동 승인 (#383). 실제 DB 잠금, 실제 반품 배송 생성(택배사는 부르지 않음),
// 실제 OutboxPublisher로 outbox_event 행을 확인한다. 커밋 뒤 Kafka 전송(OutboxRelay)만 목으로 둔다
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, OrderReturnService.class, SellerOrderCompletion.class, ShipmentService.class,
        SellerOrderEventPublisher.class, PurchaseConfirmedEventPublisher.class, EventEnvelopeMapper.class})
@ImportAutoConfiguration(OutboxAutoConfiguration.class)
@TestPropertySource(properties = "outbox.scheduler.enabled=false")
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 승인, 거절, 자동 승인이 각자 트랜잭션을 열고 커밋한다
class OrderReturnDecisionIntegrationTest {

    private static final long BUYER_ID = 10L;
    private static final long SELLER_ID = 20L;
    private static final long HOLD_MILLIS = 300;
    private static final EventEnvelopeMapper ENVELOPE_MAPPER = new EventEnvelopeMapper();

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @MockitoBean ShipmentQuery shipmentQuery; // 반품 신청에서만 씀
    @MockitoBean CourierClient courierClient;
    @MockitoBean MemberQuery memberQuery;
    @MockitoBean OrderQuery orderQuery;
    @MockitoBean ShipmentStatusChangedEventPublisher shipmentStatusChangedEventPublisher;
    @MockitoBean OutboxRelay outboxRelay;
    // 실제 빈을 감싸 잠금을 쥔 채 머무르게 할 때만 쓴다. 행은 outbox_event에 실제로 남는다
    @MockitoSpyBean SellerOrderEventPublisher sellerOrderEventPublisher;

    @Autowired OrderReturnService orderReturnService;
    @Autowired OrdersRepository ordersRepository;
    @Autowired ReturnRequestRepository returnRequestRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("truncate table outbox_event, return_request, shipment, orders_item, seller_order, orders");
    }

    @Test
    @DisplayName("판매자 승인: 주문상품 반품 배송 중, 반품 신청 승인(SELLER, 반품 배송비 3,000원), 반품 배송 READY 연결, ReturnApproved")
    void sellerApproves() {
        Fixture fixture = fixture(LocalDateTime.now().minusHours(1), OrderItemStatus.CONFIRMED);

        orderReturnService.approve(SELLER_ID, fixture.returnItemId());

        assertThat(itemStatus(fixture.returnItemId())).isEqualTo("RETURN_IN_TRANSIT");
        ReturnRequest returnRequest = returnRequest(fixture);
        assertThat(returnRequest.getStatus()).isEqualTo(ReturnRequestStatus.APPROVED);
        assertThat(returnRequest.getDecidedBy()).isEqualTo(ReturnDecidedBy.SELLER);
        assertThat(returnRequest.getDecidedAt()).isNotNull();
        assertThat(returnRequest.getReturnShippingFee()).isEqualByComparingTo("3000");
        Map<String, Object> shipment = jdbcTemplate.queryForMap(
                "select id, direction, status, seller_order_id, item_summary, courier_order_no from shipment");
        assertThat(shipment.get("id")).isEqualTo(returnRequest.getShipmentId());
        assertThat(shipment.get("direction")).isEqualTo("RETURN");
        assertThat(shipment.get("status")).isEqualTo("READY");
        assertThat(shipment.get("seller_order_id")).isEqualTo(fixture.sellerOrderId());
        assertThat(shipment.get("item_summary")).isEqualTo("몬스테라");
        assertThat(shipment.get("courier_order_no")).isNotNull();

        List<ReturnApprovedEvent> approved = outboxEvents("ReturnApproved", fixture, ReturnApprovedEvent.class);
        assertThat(approved).singleElement().satisfies(event -> {
            assertThat(event.returnRequestId()).isEqualTo(returnRequest.getId());
            assertThat(event.orderItemId()).isEqualTo(fixture.returnItemId());
            assertThat(event.orderId()).isEqualTo(fixture.orderId());
            assertThat(event.buyerId()).isEqualTo(BUYER_ID);
            assertThat(event.sellerId()).isEqualTo(SELLER_ID);
            assertThat(event.productName()).isEqualTo("몬스테라");
            assertThat(event.decidedBy()).isEqualTo(ReturnDecidedBy.SELLER);
        });
        assertThat(outboxCount("PurchaseConfirmed")).isZero(); // 반품 배송 중은 끝나지 않은 상태
    }

    @Test
    @DisplayName("승인을 두 번 눌러도 반품 배송은 1개, 두 번째는 409 RETURN_NOT_DECIDABLE")
    void secondApprovalConflicts() {
        Fixture fixture = fixture(LocalDateTime.now().minusHours(1), OrderItemStatus.CONFIRMED);
        orderReturnService.approve(SELLER_ID, fixture.returnItemId());

        assertNotDecidable(() -> orderReturnService.approve(SELLER_ID, fixture.returnItemId()));

        assertThat(returnShipmentCount()).isEqualTo(1);
        assertThat(outboxCount("ReturnApproved")).isEqualTo(1);
    }

    @Test
    @DisplayName("남의 판매자 주문의 반품은 승인, 거절 모두 404 ORDER_ITEM_NOT_FOUND")
    void otherSellerGetsNotFound() {
        Fixture fixture = fixture(LocalDateTime.now().minusHours(1), OrderItemStatus.CONFIRMED);

        assertThatThrownBy(() -> orderReturnService.approve(999L, fixture.returnItemId()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        assertThatThrownBy(() -> orderReturnService.reject(999L, fixture.returnItemId()))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        assertThat(itemStatus(fixture.returnItemId())).isEqualTo("RETURN_REQUESTED");
    }

    @Test
    @DisplayName("반품 신청이 아닌 주문상품은 승인, 거절 모두 409")
    void notRequestedItemConflicts() {
        Fixture fixture = fixture(LocalDateTime.now().minusHours(1), OrderItemStatus.CONFIRMED);

        assertNotDecidable(() -> orderReturnService.approve(SELLER_ID, fixture.otherItemId()));
        assertNotDecidable(() -> orderReturnService.reject(SELLER_ID, fixture.otherItemId()));
    }

    @Test
    @DisplayName("[S-E10] 거절한 상품이 마지막 미완료 상품이면 바로 구매 확정되고 PurchaseConfirmed가 나가며 주문이 완료된다")
    void rejectingLastUnfinishedItemSettles() {
        Fixture fixture = fixture(LocalDateTime.now().minusHours(1), OrderItemStatus.CONFIRMED);

        orderReturnService.reject(SELLER_ID, fixture.returnItemId());

        assertThat(itemStatus(fixture.returnItemId())).isEqualTo("CONFIRMED");
        ReturnRequest returnRequest = returnRequest(fixture);
        assertThat(returnRequest.getStatus()).isEqualTo(ReturnRequestStatus.REJECTED);
        assertThat(returnRequest.getDecidedBy()).isEqualTo(ReturnDecidedBy.SELLER);
        assertThat(returnRequest.getShipmentId()).isNull();
        assertThat(returnShipmentCount()).isZero();
        assertThat(outboxEvents("ReturnRejected", fixture, ReturnRejectedEvent.class)).singleElement()
                .satisfies(event -> assertThat(event.decidedBy()).isEqualTo(ReturnDecidedBy.SELLER));
        assertThat(outboxEvents("PurchaseConfirmed", fixture, PurchaseConfirmedEvent.class)).singleElement()
                .satisfies(event -> {
                    assertThat(event.saleAmount()).isEqualByComparingTo("30000"); // 두 상품 모두 구매 확정
                    assertThat(event.shippingFee()).isEqualByComparingTo("3000");
                    assertThat(event.sellerBurdenAmount()).isEqualByComparingTo("0");
                });
        assertThat(jdbcTemplate.queryForObject("select confirmed_at from seller_order where id = ?",
                LocalDateTime.class, fixture.sellerOrderId())).isNotNull();
        assertThat(ordersRepository.findById(fixture.orderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("거절해도 같은 판매자 주문에 끝나지 않은 상품이 남으면 정산 이벤트를 보내지 않는다")
    void rejectingWithUnfinishedItemDoesNotSettle() {
        Fixture fixture = fixture(LocalDateTime.now().minusHours(1), OrderItemStatus.NORMAL);

        orderReturnService.reject(SELLER_ID, fixture.returnItemId());

        assertThat(itemStatus(fixture.returnItemId())).isEqualTo("CONFIRMED");
        assertThat(outboxCount("ReturnRejected")).isEqualTo(1);
        assertThat(outboxCount("PurchaseConfirmed")).isZero();
        assertThat(ordersRepository.findById(fixture.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("신청 후 48시간이 지나면 자동 승인(AUTO)하고 반품 배송을 만든다")
    void autoApprovesAfterResponsePeriod() {
        Fixture fixture = fixture(LocalDateTime.now().minus(ReturnRequest.RESPONSE_PERIOD).minusMinutes(1),
                OrderItemStatus.CONFIRMED);

        assertThat(orderReturnService.autoApprove(fixture.returnRequestId())).isTrue();

        assertThat(itemStatus(fixture.returnItemId())).isEqualTo("RETURN_IN_TRANSIT");
        ReturnRequest returnRequest = returnRequest(fixture);
        assertThat(returnRequest.getDecidedBy()).isEqualTo(ReturnDecidedBy.AUTO);
        assertThat(returnRequest.getShipmentId()).isNotNull();
        assertThat(returnShipmentCount()).isEqualTo(1);
        assertThat(outboxEvents("ReturnApproved", fixture, ReturnApprovedEvent.class)).singleElement()
                .satisfies(event -> assertThat(event.decidedBy()).isEqualTo(ReturnDecidedBy.AUTO));
    }

    @Test
    @DisplayName("48시간이 지나지 않았거나 이미 판매자가 응답한 반품은 자동 승인하지 않는다")
    void autoApproveSkipsNotYetDueOrDecided() {
        Fixture notDue = fixture(LocalDateTime.now().minus(ReturnRequest.RESPONSE_PERIOD).plusMinutes(5),
                OrderItemStatus.CONFIRMED);
        assertThat(orderReturnService.autoApprove(notDue.returnRequestId())).isFalse();
        assertThat(itemStatus(notDue.returnItemId())).isEqualTo("RETURN_REQUESTED");

        Fixture decided = fixture(LocalDateTime.now().minus(ReturnRequest.RESPONSE_PERIOD).minusMinutes(1),
                OrderItemStatus.CONFIRMED);
        orderReturnService.reject(SELLER_ID, decided.returnItemId());
        assertThat(orderReturnService.autoApprove(decided.returnRequestId())).isFalse();

        assertThat(returnShipmentCount()).isZero();
        assertThat(outboxCount("ReturnApproved")).isZero();
    }

    @Test
    @DisplayName("자동 승인 후보는 응답 전이고 48시간이 지난 반품 신청만, 오래된 신청부터")
    void autoApproveCandidates() {
        LocalDateTime now = LocalDateTime.now();
        Fixture older = fixture(now.minusHours(60), OrderItemStatus.CONFIRMED);
        Fixture due = fixture(now.minusHours(49), OrderItemStatus.CONFIRMED);
        fixture(now.minusHours(47), OrderItemStatus.CONFIRMED); // 아직 아님
        Fixture decided = fixture(now.minusHours(70), OrderItemStatus.CONFIRMED);
        orderReturnService.reject(SELLER_ID, decided.returnItemId());

        List<Long> candidates = returnRequestRepository.findAutoApproveCandidateIds(ReturnRequestStatus.REQUESTED,
                now.minus(ReturnRequest.RESPONSE_PERIOD), 100);

        assertThat(candidates).containsExactly(older.returnRequestId(), due.returnRequestId());
    }

    @Test
    @DisplayName("판매자 거절이 먼저 주문을 잠그면 자동 승인은 그 반품을 건너뛰고, 거절만 반영된다")
    void sellerRejectLocksFirst() throws Exception {
        Fixture fixture = fixture(LocalDateTime.now().minusHours(49), OrderItemStatus.CONFIRMED);
        CompletableFuture<Void> locked = new CompletableFuture<>();
        doAnswer(invocation -> {
            locked.complete(null);
            Thread.sleep(HOLD_MILLIS); // 주문 잠금을 쥔 채 머문다
            return invocation.callRealMethod();
        }).when(sellerOrderEventPublisher).publishAfterCommit(any(ReturnRejectedEvent.class));

        ExecutorService seller = Executors.newSingleThreadExecutor();
        try {
            Future<?> rejectRun = seller.submit(() -> orderReturnService.reject(SELLER_ID, fixture.returnItemId()));
            locked.get(5, TimeUnit.SECONDS);
            assertThat(orderReturnService.autoApprove(fixture.returnRequestId())).isFalse(); // SKIP LOCKED
            rejectRun.get(5, TimeUnit.SECONDS);
            assertThat(orderReturnService.autoApprove(fixture.returnRequestId())).isFalse(); // 다음 주기: 이미 거절
        } finally {
            seller.shutdownNow();
        }

        assertRejectedOnly(fixture);
    }

    @Test
    @DisplayName("자동 승인이 먼저 주문을 잠그면 판매자 거절은 잠금을 기다린 뒤 409, 자동 승인만 반영된다")
    void autoApproveLocksFirst() throws Exception {
        Fixture fixture = fixture(LocalDateTime.now().minusHours(49), OrderItemStatus.CONFIRMED);
        CompletableFuture<Void> locked = new CompletableFuture<>();
        doAnswer(invocation -> {
            locked.complete(null);
            Thread.sleep(HOLD_MILLIS);
            return invocation.callRealMethod();
        }).when(sellerOrderEventPublisher).publishAfterCommit(any(ReturnApprovedEvent.class));

        ExecutorService scheduler = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> autoRun = scheduler.submit(() -> orderReturnService.autoApprove(fixture.returnRequestId()));
            locked.get(5, TimeUnit.SECONDS);
            assertNotDecidable(() -> orderReturnService.reject(SELLER_ID, fixture.returnItemId()));
            assertThat(autoRun.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            scheduler.shutdownNow();
        }

        assertAutoApprovedOnly(fixture);
    }

    @Test
    @DisplayName("판매자 거절과 자동 승인을 같은 순간에 시작해도 매번 둘 중 하나만 반영된다")
    void onlyOneWinsWhenStartedTogether() throws Exception {
        for (int round = 0; round < 5; round++) {
            Fixture fixture = fixture(LocalDateTime.now().minusHours(49), OrderItemStatus.CONFIRMED);
            CyclicBarrier start = new CyclicBarrier(2);
            ExecutorService seller = Executors.newSingleThreadExecutor();
            ExecutorService scheduler = Executors.newSingleThreadExecutor();
            try {
                Future<BusinessException> rejectRun = seller.submit(() -> {
                    start.await();
                    try {
                        orderReturnService.reject(SELLER_ID, fixture.returnItemId());
                        return null;
                    } catch (BusinessException e) {
                        return e;
                    }
                });
                Future<Boolean> autoRun = scheduler.submit(() -> {
                    start.await();
                    return orderReturnService.autoApprove(fixture.returnRequestId());
                });
                BusinessException rejectError = rejectRun.get(10, TimeUnit.SECONDS);
                boolean autoApproved = autoRun.get(10, TimeUnit.SECONDS);

                if (rejectError == null) {
                    assertThat(autoApproved).isFalse();
                    assertRejectedOnly(fixture);
                } else {
                    assertThat(rejectError.getErrorCode()).isEqualTo(OrderErrorCode.RETURN_NOT_DECIDABLE);
                    assertThat(autoApproved).isTrue();
                    assertAutoApprovedOnly(fixture);
                }
            } finally {
                seller.shutdownNow();
                scheduler.shutdownNow();
            }
            cleanUp();
        }
    }

    private void assertRejectedOnly(Fixture fixture) {
        assertThat(itemStatus(fixture.returnItemId())).isEqualTo("CONFIRMED");
        assertThat(returnRequest(fixture).getStatus()).isEqualTo(ReturnRequestStatus.REJECTED);
        assertThat(returnShipmentCount()).isZero();
        assertThat(outboxCount("ReturnRejected")).isEqualTo(1);
        assertThat(outboxCount("ReturnApproved")).isZero();
        assertThat(outboxCount("PurchaseConfirmed")).isEqualTo(1);
    }

    private void assertAutoApprovedOnly(Fixture fixture) {
        assertThat(itemStatus(fixture.returnItemId())).isEqualTo("RETURN_IN_TRANSIT");
        ReturnRequest returnRequest = returnRequest(fixture);
        assertThat(returnRequest.getStatus()).isEqualTo(ReturnRequestStatus.APPROVED);
        assertThat(returnRequest.getDecidedBy()).isEqualTo(ReturnDecidedBy.AUTO);
        assertThat(returnShipmentCount()).isEqualTo(1);
        assertThat(outboxCount("ReturnApproved")).isEqualTo(1);
        assertThat(outboxCount("ReturnRejected")).isZero();
        assertThat(outboxCount("PurchaseConfirmed")).isZero();
    }

    private void assertNotDecidable(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, e ->
                assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.RETURN_NOT_DECIDABLE));
    }

    private String itemStatus(Long itemId) {
        return jdbcTemplate.queryForObject("select status from orders_item where id = ?", String.class, itemId);
    }

    private ReturnRequest returnRequest(Fixture fixture) {
        return returnRequestRepository.findById(fixture.returnRequestId()).orElseThrow();
    }

    private Long returnShipmentCount() {
        return jdbcTemplate.queryForObject("select count(*) from shipment where direction = 'RETURN'", Long.class);
    }

    private Long outboxCount(String eventType) {
        return jdbcTemplate.queryForObject("select count(*) from outbox_event where event_type = ?", Long.class, eventType);
    }

    private <T> List<T> outboxEvents(String eventType, Fixture fixture, Class<T> type) {
        return jdbcTemplate.queryForList("""
                        select payload from outbox_event
                        where event_type = ? and topic = 'market.events.v1' and aggregate_id = ?
                        order by created_at""", String.class, eventType, String.valueOf(fixture.sellerOrderId()))
                .stream()
                .map(json -> ENVELOPE_MAPPER.readPayload(ENVELOPE_MAPPER.fromJson(json), type))
                .toList();
    }

    // 결제 완료 주문 1건, 판매자 주문 1건(배송비 3,000원), 반품 신청한 주문상품(몬스테라 10,000원)과
    // 다른 주문상품(20,000원, 상태 otherStatus). 반품 신청 시각은 requestedAt
    private Fixture fixture(LocalDateTime requestedAt, OrderItemStatus otherStatus) {
        Orders order = new Orders("ORD-RD-" + System.nanoTime(), BUYER_ID, "구매자", "01012345678", "12345", "서울시", null, 2);
        SellerOrder sellerOrder = new SellerOrder(order, SELLER_ID);
        OrdersItem returnItem = new OrdersItem(sellerOrder, 100L, "몬스테라", new BigDecimal("10000"));
        OrdersItem otherItem = new OrdersItem(sellerOrder, 101L, "필레아", new BigDecimal("20000"));
        sellerOrder.addItems(List.of(returnItem, otherItem));
        sellerOrder.setShippingFee(SellerOrder.DEFAULT_SHIPPING_FEE);
        order.addSellerOrders(List.of(sellerOrder));
        order.paid();
        returnItem.requestReturn();
        ReflectionTestUtils.setField(otherItem, "status", otherStatus);
        ordersRepository.save(order);
        ReturnRequest returnRequest = returnRequestRepository.save(new ReturnRequest(returnItem.getId(),
                sellerOrder.getId(), ReturnReason.DAMAGED, null, requestedAt));
        return new Fixture(order.getId(), sellerOrder.getId(), returnItem.getId(), otherItem.getId(),
                returnRequest.getId());
    }

    private record Fixture(Long orderId, Long sellerOrderId, Long returnItemId, Long otherItemId,
                           Long returnRequestId) {
    }
}
