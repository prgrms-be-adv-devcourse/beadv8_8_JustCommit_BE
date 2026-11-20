package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.ReturnReason;
import com.justcommit.backend.market.ReturnRequestedEvent;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.AutoConfirmTarget;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.PurchaseConfirmedEventPublisher;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import com.justcommit.backend.market.shipment.application.ShipmentQueryService;
import com.justcommit.backend.market.shipment.domain.ShipmentDirection;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 구매자 반품 신청 (#382)을 실제 Postgres와 실제 배송 조회로 확인한다
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, OrderReturnService.class, OrderItemConfirmationService.class, ShipmentQueryService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderReturnIntegrationTest {

    private static final long BUYER_ID = 10L;
    private static final long SELLER_ID = 20L;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @MockitoBean SellerOrderEventPublisher sellerOrderEventPublisher;
    @MockitoBean PurchaseConfirmedEventPublisher purchaseConfirmedEventPublisher;

    @Autowired OrderReturnService orderReturnService;
    @Autowired OrderItemConfirmationService confirmationService;
    @Autowired OrdersRepository ordersRepository;
    @Autowired OrdersItemRepository ordersItemRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("truncate table return_request, shipment, orders_item, seller_order, orders");
    }

    @Test
    @DisplayName("배송 완료된 일반 주문상품에 반품을 신청하면 반품 신청이 저장되고 주문상품은 반품 신청, ReturnRequested가 나간다")
    void requestsReturn() {
        Fixture fixture = deliveredOrder(LocalDateTime.now().minusDays(1));

        orderReturnService.request(BUYER_ID, fixture.firstItemId(), ReturnReason.DAMAGED, "잎이 부러져 왔어요");

        assertThat(itemStatus(fixture.firstItemId())).isEqualTo("RETURN_REQUESTED");
        assertThat(itemStatus(fixture.secondItemId())).isEqualTo("NORMAL");
        assertThat(jdbcTemplate.queryForMap("""
                select order_item_id, seller_order_id, reason, reason_detail, status, decided_by, requested_at
                  from return_request""", (Object[]) null))
                .containsEntry("order_item_id", fixture.firstItemId())
                .containsEntry("seller_order_id", fixture.sellerOrderId())
                .containsEntry("reason", "DAMAGED")
                .containsEntry("reason_detail", "잎이 부러져 왔어요")
                .containsEntry("status", "REQUESTED")
                .containsEntry("decided_by", null)
                .satisfies(row -> assertThat(row.get("requested_at")).isNotNull());
        // 정산 기준액은 그대로 (반품 신청도 거절되면 구매 확정되는 유효 상품)
        assertThat(jdbcTemplate.queryForObject("select settlement_base_amount from seller_order", BigDecimal.class))
                .isEqualByComparingTo("15000");

        ArgumentCaptor<ReturnRequestedEvent> event = ArgumentCaptor.forClass(ReturnRequestedEvent.class);
        verify(sellerOrderEventPublisher).publishAfterCommit(event.capture());
        assertThat(event.getValue()).satisfies(e -> {
            assertThat(e.returnRequestId()).isNotNull();
            assertThat(e.orderItemId()).isEqualTo(fixture.firstItemId());
            assertThat(e.orderId()).isEqualTo(fixture.orderId());
            assertThat(e.sellerOrderId()).isEqualTo(fixture.sellerOrderId());
            assertThat(e.buyerId()).isEqualTo(BUYER_ID);
            assertThat(e.sellerId()).isEqualTo(SELLER_ID);
            assertThat(e.productName()).isEqualTo("몬스테라");
            assertThat(e.reason()).isEqualTo(ReturnReason.DAMAGED);
            assertThat(e.requestedAt()).isNotNull();
            assertThat(e.eventType()).isEqualTo("ReturnRequested");
            assertThat(e.aggregateId()).isEqualTo(String.valueOf(fixture.sellerOrderId()));
        });
    }

    @Test
    @DisplayName("남의 주문상품이면 기존처럼 404")
    void otherBuyersItemIsNotFound() {
        Fixture fixture = deliveredOrder(LocalDateTime.now().minusDays(1));

        assertError(() -> orderReturnService.request(99L, fixture.firstItemId(), ReturnReason.DAMAGED, null),
                OrderErrorCode.ORDER_ITEM_NOT_FOUND);
        assertNothingSaved(fixture);
    }

    @Test
    @DisplayName("이미 반품을 신청한 주문상품은 반품 불가가 아니라 이미 신청함(409)")
    void alreadyRequestedComesBeforeNotRequestable() {
        Fixture fixture = deliveredOrder(LocalDateTime.now().minusDays(1));
        orderReturnService.request(BUYER_ID, fixture.firstItemId(), ReturnReason.DAMAGED, null);

        assertError(() -> orderReturnService.request(BUYER_ID, fixture.firstItemId(), ReturnReason.OTHER, "다시"),
                OrderErrorCode.RETURN_ALREADY_REQUESTED);
        assertThat(jdbcTemplate.queryForObject("select count(*) from return_request", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("반품 배송 중, 반품 환불 중, 반품 완료 주문상품도 이미 신청함(409)")
    void returnStatusesAreAlreadyRequested() {
        Fixture fixture = deliveredOrder(LocalDateTime.now().minusDays(1));
        for (String status : List.of("RETURN_IN_TRANSIT", "RETURN_PROCESSING", "RETURNED")) {
            jdbcTemplate.update("update orders_item set status = ? where id = ?", status, fixture.firstItemId());

            assertError(() -> orderReturnService.request(BUYER_ID, fixture.firstItemId(), ReturnReason.DAMAGED, null),
                    OrderErrorCode.RETURN_ALREADY_REQUESTED);
        }
    }

    @Test
    @DisplayName("반품 신청 행이 이미 있으면 주문상품이 일반이어도 유니크 위반을 이미 신청함(409)으로 바꾼다")
    void uniqueViolationIsAlreadyRequested() {
        Fixture fixture = deliveredOrder(LocalDateTime.now().minusDays(1));
        jdbcTemplate.update("""
                insert into return_request (order_item_id, seller_order_id, reason, status, requested_at)
                values (?, ?, 'DAMAGED', 'REQUESTED', ?)""",
                fixture.firstItemId(), fixture.sellerOrderId(), LocalDateTime.now());

        assertError(() -> orderReturnService.request(BUYER_ID, fixture.firstItemId(), ReturnReason.DAMAGED, null),
                OrderErrorCode.RETURN_ALREADY_REQUESTED);
        assertThat(itemStatus(fixture.firstItemId())).isEqualTo("NORMAL");
        verify(sellerOrderEventPublisher, never()).publishAfterCommit(any(ReturnRequestedEvent.class));
    }

    @Test
    @DisplayName("주문이 결제 완료가 아니면 반품 불가(409)")
    void orderNotPaidIsNotRequestable() {
        Fixture fixture = deliveredOrder(LocalDateTime.now().minusDays(1));
        jdbcTemplate.update("update orders set status = 'COMPLETED'");

        assertError(() -> orderReturnService.request(BUYER_ID, fixture.firstItemId(), ReturnReason.DAMAGED, null),
                OrderErrorCode.RETURN_NOT_REQUESTABLE);
        assertNothingSaved(fixture);
    }

    @Test
    @DisplayName("일반이 아닌 주문상품(구매 확정, 취소 중, 취소 완료, 수락 대기)은 반품 불가(409)")
    void itemNotNormalIsNotRequestable() {
        Fixture fixture = deliveredOrder(LocalDateTime.now().minusDays(1));
        for (String status : List.of("CONFIRMED", "CANCEL_PROCESSING", "CANCELLED", "ACCEPT_PENDING", "REVIEW_REQUIRED")) {
            jdbcTemplate.update("update orders_item set status = ? where id = ?", status, fixture.firstItemId());

            assertError(() -> orderReturnService.request(BUYER_ID, fixture.firstItemId(), ReturnReason.DAMAGED, null),
                    OrderErrorCode.RETURN_NOT_REQUESTABLE);
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from return_request", Long.class)).isZero();
    }

    @Test
    @DisplayName("가는 길 배송이 배송 완료가 아니거나 없으면 반품 불가(409). 반품 배송의 상태는 보지 않는다")
    void outboundNotDeliveredIsNotRequestable() {
        Fixture fixture = deliveredOrder(LocalDateTime.now().minusDays(1));
        jdbcTemplate.update("update shipment set status = 'IN_TRANSIT', delivered_at = null where direction = 'OUTBOUND'");
        insertReturnShipment(fixture.sellerOrderId(), "DELIVERED");

        assertError(() -> orderReturnService.request(BUYER_ID, fixture.firstItemId(), ReturnReason.DAMAGED, null),
                OrderErrorCode.RETURN_NOT_REQUESTABLE);

        jdbcTemplate.update("delete from shipment where direction = 'OUTBOUND'");
        assertError(() -> orderReturnService.request(BUYER_ID, fixture.firstItemId(), ReturnReason.DAMAGED, null),
                OrderErrorCode.RETURN_NOT_REQUESTABLE);
        assertNothingSaved(fixture);
    }

    @Test
    @DisplayName("72시간 경계: 배송 완료 + 3일 직전은 신청되고, 직후는 기간 지남(409)")
    void returnPeriodBoundary() {
        Fixture justBefore = deliveredOrder(LocalDateTime.now().minusDays(3).plusMinutes(1));
        orderReturnService.request(BUYER_ID, justBefore.firstItemId(), ReturnReason.DAMAGED, null);
        assertThat(itemStatus(justBefore.firstItemId())).isEqualTo("RETURN_REQUESTED");

        cleanUp();
        clearInvocations(sellerOrderEventPublisher);
        Fixture justAfter = deliveredOrder(LocalDateTime.now().minusDays(3).minusSeconds(1));
        assertError(() -> orderReturnService.request(BUYER_ID, justAfter.firstItemId(), ReturnReason.DAMAGED, null),
                OrderErrorCode.RETURN_PERIOD_EXPIRED);
        assertNothingSaved(justAfter);
    }

    @Test
    @DisplayName("반품 신청한 주문상품은 자동 구매 확정 후보에서 빠지고, 후보 조회 뒤 신청돼도 자동 구매 확정이 건너뛴다")
    void returnRequestedItemIsExcludedFromAutoConfirmation() {
        Fixture fixture = deliveredOrder(LocalDateTime.now().minusDays(1));
        orderReturnService.request(BUYER_ID, fixture.firstItemId(), ReturnReason.DAMAGED, null);
        // 반품 기간이 지난 것처럼 배송 완료 시각을 당긴다
        jdbcTemplate.update("update shipment set delivered_at = ?", LocalDateTime.now().minusDays(4));

        assertThat(autoConfirmTargets()).extracting(AutoConfirmTarget::orderItemId)
                .containsExactly(fixture.secondItemId());

        confirmationService.autoConfirmOrder(fixture.orderId(), List.of(fixture.firstItemId(), fixture.secondItemId()));

        assertThat(itemStatus(fixture.firstItemId())).isEqualTo("RETURN_REQUESTED");
        assertThat(itemStatus(fixture.secondItemId())).isEqualTo("CONFIRMED");
        // 반품 신청이 남아 판매자 주문은 끝나지 않는다 (정산 이벤트 없음)
        assertThat(jdbcTemplate.queryForObject("select confirmed_at from seller_order", LocalDateTime.class)).isNull();
        assertThat(jdbcTemplate.queryForObject("select status from orders", String.class)).isEqualTo("PAID");
        verify(purchaseConfirmedEventPublisher, never()).publishAfterCommit(any());
    }

    @Test
    @DisplayName("반품 배송 행이 있어도 자동 구매 확정 후보는 주문상품마다 한 번, 직접 구매 확정은 가는 길 배송으로 판정한다")
    void returnShipmentRowsDoNotAffectConfirmation() {
        Fixture fixture = deliveredOrder(LocalDateTime.now().minusDays(4));
        insertReturnShipment(fixture.sellerOrderId(), "READY");
        insertReturnShipment(fixture.sellerOrderId(), "READY");

        assertThat(autoConfirmTargets()).extracting(AutoConfirmTarget::orderItemId)
                .containsExactly(fixture.firstItemId(), fixture.secondItemId());

        confirmationService.confirm(BUYER_ID, fixture.firstItemId());
        confirmationService.confirm(BUYER_ID, fixture.secondItemId());

        assertThat(jdbcTemplate.queryForList("select status from orders_item", String.class)).containsOnly("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject("select status from orders", String.class)).isEqualTo("COMPLETED");
    }

    private List<AutoConfirmTarget> autoConfirmTargets() {
        return ordersItemRepository.findAutoConfirmTargets(OrderStatus.PAID, OrderItemStatus.NORMAL,
                ShipmentStatus.DELIVERED, LocalDateTime.now().minus(OrdersItem.RETURN_PERIOD), ShipmentDirection.OUTBOUND);
    }

    private void assertNothingSaved(Fixture fixture) {
        assertThat(jdbcTemplate.queryForObject("select count(*) from return_request", Long.class)).isZero();
        assertThat(itemStatus(fixture.firstItemId())).isNotEqualTo("RETURN_REQUESTED");
        verify(sellerOrderEventPublisher, never()).publishAfterCommit(any(ReturnRequestedEvent.class));
    }

    private static void assertError(Runnable call, OrderErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private String itemStatus(Long itemId) {
        return jdbcTemplate.queryForObject("select status from orders_item where id = ?", String.class, itemId);
    }

    private void insertReturnShipment(Long sellerOrderId, String status) {
        jdbcTemplate.update("insert into shipment (seller_order_id, status, direction, version) values (?, ?, 'RETURN', 0)",
                sellerOrderId, status);
    }

    // 결제 완료 주문 1건, 판매자 주문 1건, 일반 주문상품 2건, 가는 길 배송 완료
    private Fixture deliveredOrder(LocalDateTime deliveredAt) {
        Orders order = new Orders("ORD-R-" + System.nanoTime(), BUYER_ID, "구매자", "01012345678", "12345", "서울시", null, 2);
        SellerOrder sellerOrder = new SellerOrder(order, SELLER_ID);
        OrdersItem first = new OrdersItem(sellerOrder, 100L, "몬스테라", new BigDecimal("10000"));
        OrdersItem second = new OrdersItem(sellerOrder, 101L, "선인장", new BigDecimal("5000"));
        sellerOrder.addItems(List.of(first, second));
        order.addSellerOrders(List.of(sellerOrder));
        order.paid();
        ordersRepository.save(order);
        jdbcTemplate.update("insert into shipment (seller_order_id, status, delivered_at, version) values (?, 'DELIVERED', ?, 0)",
                sellerOrder.getId(), deliveredAt);
        return new Fixture(order.getId(), sellerOrder.getId(), first.getId(), second.getId());
    }

    private record Fixture(Long orderId, Long sellerOrderId, Long firstItemId, Long secondItemId) {
    }
}
