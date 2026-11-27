package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.PurchaseConfirmedEvent;
import com.justcommit.backend.market.ReturnDecidedBy;
import com.justcommit.backend.market.ReturnReason;
import com.justcommit.backend.market.SellerOrderReturnedEvent;
import com.justcommit.backend.market.config.JpaAuditingConfig;
import com.justcommit.backend.market.order.application.SettlementTargetQueryService.SettlementTarget;
import com.justcommit.backend.market.order.application.SettlementTargetQueryService.SettlementTargetPage;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.ReturnRequest;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.domain.SellerOrderSettlementAmounts;
import com.justcommit.backend.market.order.infrastructure.kafka.PurchaseConfirmedEventPublisher;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 정산 대상 조회 (#657)를 실제 PostgreSQL에서 확인.
// 판매자 주문을 실제 SellerOrderCompletion으로 끝내고(flush, clear 뒤 조회 서비스가 DB를 다시 읽음),
// 발행기에 넘긴 이벤트의 종류와 금액이 조회 결과와 같은지 본다
@DataJpaTest(properties = "spring.jpa.properties.hibernate.check_nullability=false")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SettlementTargetQueryService.class, SellerOrderCompletion.class})
@Testcontainers(disabledWithoutDocker = true)
class SettlementTargetQueryIntegrationTest {

    private static final long SELLER = 20L;
    private static final long OTHER_SELLER = 21L;
    private static final BigDecimal PRICE = new BigDecimal("10000");
    private static final BigDecimal SHIPPING_FEE = new BigDecimal("3000");
    private static final BigDecimal RETURN_SHIPPING_FEE = new BigDecimal("4000");
    // 기간 조회 테스트의 기준. 다른 테스트가 넣은 행(confirmedAt = 지금)과 겹치지 않게 과거로 둔다
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 1, 2, 0, 0);

    @Autowired EntityManager entityManager;
    @Autowired SettlementTargetQueryService service;
    @Autowired SellerOrderCompletion completion;

    @MockitoBean PurchaseConfirmedEventPublisher purchaseConfirmedEventPublisher;
    @MockitoBean SellerOrderEventPublisher sellerOrderEventPublisher;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @Test
    @DisplayName("구매 확정만: PURCHASE_CONFIRMED, 금액이 PurchaseConfirmed 이벤트와 같다 (판매자 부담금 0)")
    void purchaseConfirmedOnly() {
        long id = completeSellerOrder(OrderItemStatus.CONFIRMED, OrderItemStatus.CONFIRMED);

        SettlementTarget target = findOne(id);

        assertSameAsPurchaseConfirmed(target, purchaseConfirmedEvent());
        assertAmounts(target, "20000", "3000", "0");
    }

    @Test
    @DisplayName("구매 확정 + 취소: PURCHASE_CONFIRMED, 판매액에서 취소 상품이 빠진다")
    void confirmedAndCancelled() {
        long id = completeSellerOrder(OrderItemStatus.CONFIRMED, OrderItemStatus.CANCELLED, OrderItemStatus.CANCELLED);

        SettlementTarget target = findOne(id);

        assertSameAsPurchaseConfirmed(target, purchaseConfirmedEvent());
        assertAmounts(target, "10000", "3000", "0");
    }

    @Test
    @DisplayName("일부 반품: PURCHASE_CONFIRMED, 판매자 부담금 = 완료된 반품 배송비 합 (거절된 반품은 빠짐), 이벤트와 같다")
    void partiallyReturned() {
        long id = completeSellerOrder(OrderItemStatus.CONFIRMED, OrderItemStatus.RETURNED, OrderItemStatus.RETURNED,
                OrderItemStatus.CANCELLED);

        SettlementTarget target = findOne(id);

        assertSameAsPurchaseConfirmed(target, purchaseConfirmedEvent());
        assertAmounts(target, "10000", "3000", "8000");
    }

    @Test
    @DisplayName("전부 반품: RETURNED, SellerOrderReturned와 같다. 판매자 주문의 배송비(3000)는 있어도 응답 배송비는 0")
    void allReturned() {
        long id = completeSellerOrder(OrderItemStatus.RETURNED, OrderItemStatus.RETURNED);

        SettlementTarget target = findOne(id);

        assertSameAsSellerOrderReturned(target, sellerOrderReturnedEvent());
        // 판매자 부담금 = 반품 배송비 4000 * 2 + 원래 배송비 3000 (원래 배송비는 구매자에게 환불돼 응답 배송비는 0)
        assertAmounts(target, "0", "0", "11000");
    }

    @Test
    @DisplayName("반품 + 나머지 취소(수거 전 일부 취소): 구매 확정이 없어 RETURNED, SellerOrderReturned와 같다")
    void returnedAndCancelled() {
        long id = completeSellerOrder(OrderItemStatus.RETURNED, OrderItemStatus.CANCELLED);

        SettlementTarget target = findOne(id);

        assertSameAsSellerOrderReturned(target, sellerOrderReturnedEvent());
        assertAmounts(target, "0", "0", "7000");
    }

    @Test
    @DisplayName("모두 취소: 이벤트도 confirmedAt도 없어 조회되지 않는다")
    void allCancelledIsExcluded() {
        long id = completeSellerOrder(OrderItemStatus.CANCELLED, OrderItemStatus.CANCELLED);

        assertNoEvent();
        assertThat(entityManager.find(SellerOrder.class, id).getConfirmedAt()).isNull();
        assertThat(findAllAroundNow()).extracting(SettlementTarget::sellerOrderId).doesNotContain(id);
    }

    @ParameterizedTest
    @EnumSource(value = OrderItemStatus.class, names = {"ACCEPT_PENDING", "NORMAL", "CANCEL_PROCESSING",
            "REVIEW_REQUIRED", "RETURN_REQUESTED", "RETURN_IN_TRANSIT", "RETURN_PROCESSING"})
    @DisplayName("끝나지 않은 주문상품이 남은 판매자 주문은 이벤트도 confirmedAt도 없어 조회되지 않는다")
    void unfinishedIsExcluded(OrderItemStatus unfinished) {
        long id = completeSellerOrder(OrderItemStatus.CONFIRMED, unfinished);

        assertNoEvent();
        assertThat(entityManager.find(SellerOrder.class, id).getConfirmedAt()).isNull();
        assertThat(findAllAroundNow()).extracting(SettlementTarget::sellerOrderId).doesNotContain(id);
    }

    @Test
    @DisplayName("같은 주문의 다른 판매자 주문이 진행 중이어도 끝난 판매자 주문은 조회되고, 진행 중인 쪽은 빠진다")
    void otherSellerOrderInProgress() {
        List<Long> ids = saveOrder(
                new OrderItemStatus[]{OrderItemStatus.CONFIRMED},
                new OrderItemStatus[]{OrderItemStatus.RETURN_IN_TRANSIT, OrderItemStatus.CONFIRMED});
        long finished = ids.get(0);
        long inProgress = ids.get(1);

        complete(finished);

        SettlementTarget target = findOne(finished);
        assertSameAsPurchaseConfirmed(target, purchaseConfirmedEvent());
        assertAmounts(target, "10000", "3000", "0");
        assertThat(findAllAroundNow()).extracting(SettlementTarget::sellerOrderId).doesNotContain(inProgress);
        // 주문은 다른 판매자 주문이 끝날 때까지 결제 완료로 남는다
        Long orderId = entityManager.find(SellerOrder.class, finished).getOrder().getId();
        assertThat(entityManager.find(Orders.class, orderId).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("기간 경계: from과 같은 confirmedAt은 포함, to와 같은 confirmedAt은 제외")
    void rangeBoundary() {
        long beforeFrom = completeSellerOrderAt(FROM.minusNanos(1_000));
        long atFrom = completeSellerOrderAt(FROM);
        long beforeTo = completeSellerOrderAt(TO.minusNanos(1_000));
        long atTo = completeSellerOrderAt(TO);

        SettlementTargetPage page = service.find(FROM, TO, null, 100);

        assertThat(page.items()).extracting(SettlementTarget::sellerOrderId).containsExactly(atFrom, beforeTo)
                .doesNotContain(beforeFrom, atTo);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("from == to면 빈 기간이라 from과 같은 confirmedAt도 나오지 않는다")
    void emptyRange() {
        completeSellerOrderAt(FROM);

        SettlementTargetPage page = service.find(FROM, FROM, null, 100);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("커서로 여러 페이지를 끝까지: sellerOrderId 오름차순, 빠짐과 중복 없이, 마지막 페이지의 nextCursor는 null")
    void cursorPages() {
        List<Long> expected = new ArrayList<>();
        // confirmedAt 순서와 id 순서가 달라도 id 순으로 나온다
        for (int i = 0; i < 7; i++) {
            expected.add(completeSellerOrderAt(FROM.plusHours(20 - i)));
        }

        List<Long> actual = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        List<Long> cursors = new ArrayList<>();
        Long cursor = null;
        do {
            SettlementTargetPage page = service.find(FROM, TO, cursor, 3);
            page.items().forEach(t -> actual.add(t.sellerOrderId()));
            pageSizes.add(page.items().size());
            cursor = page.nextCursor();
            cursors.add(cursor);
        } while (cursor != null);

        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(pageSizes).containsExactly(3, 3, 1);
        // nextCursor = 그 페이지의 마지막 sellerOrderId, 마지막 페이지는 null
        assertThat(cursors).containsExactly(expected.get(2), expected.get(5), null);
    }

    @Test
    @DisplayName("개수가 size와 딱 맞으면 다음 페이지 없이 한 번에 끝난다")
    void exactPageHasNoNextCursor() {
        completeSellerOrderAt(FROM.plusHours(1));
        completeSellerOrderAt(FROM.plusHours(2));

        SettlementTargetPage page = service.find(FROM, TO, null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    private static void assertSameAsPurchaseConfirmed(SettlementTarget target, PurchaseConfirmedEvent event) {
        assertThat(target.sellerOrderId()).isEqualTo(event.sellerOrderId());
        assertThat(target.sellerId()).isEqualTo(event.sellerId());
        assertThat(target.amounts().type()).isEqualTo(SellerOrderSettlementAmounts.Type.PURCHASE_CONFIRMED);
        assertThat(target.amounts().saleAmount()).isEqualByComparingTo(event.saleAmount());
        assertThat(target.amounts().shippingFee()).isEqualByComparingTo(event.shippingFee());
        assertThat(target.amounts().sellerBurdenAmount()).isEqualByComparingTo(event.sellerBurdenAmount());
        assertThat(target.confirmedAt()).isEqualTo(roundedToMicros(event.confirmedAt()));
    }

    // SellerOrderReturned에는 판매액, 배송비가 없다. 응답은 둘 다 0이어야 한다 (assertAmounts로 확인)
    private static void assertSameAsSellerOrderReturned(SettlementTarget target, SellerOrderReturnedEvent event) {
        assertThat(target.sellerOrderId()).isEqualTo(event.sellerOrderId());
        assertThat(target.sellerId()).isEqualTo(event.sellerId());
        assertThat(target.amounts().type()).isEqualTo(SellerOrderSettlementAmounts.Type.RETURNED);
        assertThat(target.amounts().sellerBurdenAmount()).isEqualByComparingTo(event.sellerBurdenAmount());
        assertThat(target.confirmedAt()).isEqualTo(roundedToMicros(event.returnedAt()));
    }

    private static void assertAmounts(SettlementTarget target, String saleAmount, String shippingFee,
                                      String sellerBurdenAmount) {
        assertThat(target.amounts().saleAmount()).isEqualByComparingTo(saleAmount);
        assertThat(target.amounts().shippingFee()).isEqualByComparingTo(shippingFee);
        assertThat(target.amounts().sellerBurdenAmount()).isEqualByComparingTo(sellerBurdenAmount);
    }

    // PostgreSQL timestamp(6)는 마이크로초까지 저장하고, JDBC 드라이버는 나노초를 마이크로초로 반올림해 보낸다 (버림이 아님)
    private static LocalDateTime roundedToMicros(LocalDateTime time) {
        return time.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
    }

    private PurchaseConfirmedEvent purchaseConfirmedEvent() {
        ArgumentCaptor<PurchaseConfirmedEvent> captor = ArgumentCaptor.forClass(PurchaseConfirmedEvent.class);
        verify(purchaseConfirmedEventPublisher).publishAfterCommit(captor.capture());
        verify(sellerOrderEventPublisher, never()).publishAfterCommit(any(SellerOrderReturnedEvent.class));
        return captor.getValue();
    }

    private SellerOrderReturnedEvent sellerOrderReturnedEvent() {
        ArgumentCaptor<SellerOrderReturnedEvent> captor = ArgumentCaptor.forClass(SellerOrderReturnedEvent.class);
        verify(sellerOrderEventPublisher).publishAfterCommit(captor.capture());
        verify(purchaseConfirmedEventPublisher, never()).publishAfterCommit(any());
        return captor.getValue();
    }

    private void assertNoEvent() {
        verify(purchaseConfirmedEventPublisher, never()).publishAfterCommit(any());
        verify(sellerOrderEventPublisher, never()).publishAfterCommit(any(SellerOrderReturnedEvent.class));
    }

    private SettlementTarget findOne(long sellerOrderId) {
        List<SettlementTarget> found = findAllAroundNow().stream()
                .filter(t -> t.sellerOrderId() == sellerOrderId).toList();
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private List<SettlementTarget> findAllAroundNow() {
        LocalDateTime now = LocalDateTime.now();
        return service.find(now.minusHours(1), now.plusHours(1), null, 100).items();
    }

    // 구매 확정 판매자 주문을 끝내고 confirmedAt(종료 시각)을 정해진 시각으로 바꾼다
    private long completeSellerOrderAt(LocalDateTime confirmedAt) {
        long id = completeSellerOrder(OrderItemStatus.CONFIRMED);
        entityManager.createNativeQuery("update seller_order set confirmed_at = :at where id = :id")
                .setParameter("at", confirmedAt)
                .setParameter("id", id)
                .executeUpdate();
        entityManager.clear();
        return id;
    }

    // 판매자 한 명짜리 주문을 저장하고 SellerOrderCompletion으로 끝낸다
    private long completeSellerOrder(OrderItemStatus... statuses) {
        long id = saveOrder(statuses).getFirst();
        complete(id);
        return id;
    }

    // 주문상품 상태가 바뀐 뒤 서비스가 하듯 SellerOrderCompletion을 부르고, 조회 전에 flush, clear 한다
    private void complete(long sellerOrderId) {
        SellerOrder sellerOrder = entityManager.find(SellerOrder.class, sellerOrderId);
        completion.complete(sellerOrder.getOrder(), sellerOrder);
        entityManager.flush();
        entityManager.clear();
    }

    private int orderSeq = 0;

    // 결제된 주문 하나를 판매자별 주문상품 상태로 저장한다 (판매자 주문마다 배송비 3000, 상품 10000).
    // 반품 신청은 실제 흐름대로 맞춘다: 반품 완료 → COMPLETED(반품 배송비 4000), 반품 배송 중, 환불 중 → APPROVED(4000),
    // 반품 신청 → REQUESTED. 구매 확정 상품에는 거절된 반품 신청(REJECTED, 배송비 없음)을 붙여 합에서 빠지는지 본다
    private List<Long> saveOrder(OrderItemStatus[]... sellers) {
        Orders order = new Orders("ORD-st-" + (++orderSeq) + "-" + System.nanoTime(), 1L, "구매자", "010", "12345",
                "서울", null, 1);
        List<SellerOrder> sellerOrders = new ArrayList<>();
        List<OrdersItem> allItems = new ArrayList<>();
        List<OrderItemStatus> allStatuses = new ArrayList<>();
        long productId = orderSeq * 100L;
        for (int s = 0; s < sellers.length; s++) {
            SellerOrder sellerOrder = new SellerOrder(order, s == 0 ? SELLER : OTHER_SELLER);
            List<OrdersItem> items = new ArrayList<>();
            for (OrderItemStatus status : sellers[s]) {
                OrdersItem item = new OrdersItem(sellerOrder, ++productId, "식물" + productId, PRICE);
                items.add(item);
                allItems.add(item);
                allStatuses.add(status);
            }
            sellerOrder.addItems(items);
            sellerOrder.setShippingFee(SHIPPING_FEE);
            sellerOrders.add(sellerOrder);
        }
        order.addSellerOrders(sellerOrders);
        entityManager.persist(order);
        entityManager.flush();

        // 엔티티에 해당 상태로 바꾸는 메서드가 없는 상태도 있어 저장 뒤 SQL로 맞춘다
        entityManager.createNativeQuery("update orders set status = :status where id = :id")
                .setParameter("status", OrderStatus.PAID.name())
                .setParameter("id", order.getId())
                .executeUpdate();
        for (int i = 0; i < allItems.size(); i++) {
            OrdersItem item = allItems.get(i);
            OrderItemStatus status = allStatuses.get(i);
            entityManager.createNativeQuery("update orders_item set status = :status where id = :id")
                    .setParameter("status", status.name())
                    .setParameter("id", item.getId())
                    .executeUpdate();
            saveReturnRequest(item, status);
        }
        entityManager.flush();
        entityManager.clear();
        return sellerOrders.stream().map(SellerOrder::getId).toList();
    }

    private void saveReturnRequest(OrdersItem item, OrderItemStatus status) {
        LocalDateTime now = LocalDateTime.now();
        ReturnRequest request = new ReturnRequest(item.getId(), item.getSellerOrder().getId(), ReturnReason.OTHER,
                null, now);
        switch (status) {
            case RETURNED -> {
                request.approve(ReturnDecidedBy.SELLER, now, RETURN_SHIPPING_FEE, null);
                request.complete();
            }
            case RETURN_IN_TRANSIT, RETURN_PROCESSING ->
                    request.approve(ReturnDecidedBy.SELLER, now, RETURN_SHIPPING_FEE, null);
            case RETURN_REQUESTED -> {
                // 신청 상태 그대로
            }
            case CONFIRMED -> request.reject(ReturnDecidedBy.SELLER, now);
            default -> {
                return;
            }
        }
        entityManager.persist(request);
    }
}
