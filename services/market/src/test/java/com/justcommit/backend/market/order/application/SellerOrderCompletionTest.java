package com.justcommit.backend.market.order.application;

import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.PurchaseConfirmedEvent;
import com.justcommit.backend.market.SellerOrderReturnedEvent;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.ReturnRequestStatus;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.ReturnRequestRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.PurchaseConfirmedEventPublisher;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 판매자 주문 종료 판정 (#383): 이벤트 종류와 금액, 같은 판매자 주문에 정산 이벤트가 두 번 나가지 않는지, 주문 상태.
// 반품 완료(RETURNED)와 COMPLETED 반품 신청은 #384에서 생기므로 픽스처로 만든다
@ExtendWith(MockitoExtension.class)
class SellerOrderCompletionTest {

    private static final long SELLER_ORDER_ID = 7L;

    @Mock ReturnRequestRepository returnRequestRepository;
    @Mock PurchaseConfirmedEventPublisher purchaseConfirmedEventPublisher;
    @Mock SellerOrderEventPublisher sellerOrderEventPublisher;
    SellerOrderCompletion completion;

    @BeforeEach
    void setUp() {
        completion = new SellerOrderCompletion(returnRequestRepository, purchaseConfirmedEventPublisher,
                sellerOrderEventPublisher);
    }

    @Test
    @DisplayName("구매 확정 + 반품 완료가 섞이면 PurchaseConfirmed: 판매액 = 구매 확정 상품 합, 판매자 부담금 = 반품 배송비 합")
    void confirmedAndReturnedPublishesPurchaseConfirmed() {
        SellerOrder sellerOrder = sellerOrder(OrderItemStatus.CONFIRMED, OrderItemStatus.RETURNED, OrderItemStatus.RETURNED);
        returnShippingFees("6000");

        completion.complete(sellerOrder.getOrder(), sellerOrder);

        ArgumentCaptor<PurchaseConfirmedEvent> event = ArgumentCaptor.forClass(PurchaseConfirmedEvent.class);
        verify(purchaseConfirmedEventPublisher).publishAfterCommit(event.capture());
        assertThat(event.getValue().sellerOrderId()).isEqualTo(SELLER_ORDER_ID);
        assertThat(event.getValue().sellerId()).isEqualTo(20L);
        assertThat(event.getValue().saleAmount()).isEqualByComparingTo("10000");
        assertThat(event.getValue().shippingFee()).isEqualByComparingTo("3000");
        assertThat(event.getValue().sellerBurdenAmount()).isEqualByComparingTo("6000");
        assertThat(event.getValue().confirmedAt()).isEqualTo(sellerOrder.getConfirmedAt());
        assertThat(event.getValue().itemSummary()).isEqualTo("상품1");
        verify(sellerOrderEventPublisher, never()).publishAfterCommit(any(SellerOrderReturnedEvent.class));
        verify(returnRequestRepository).sumReturnShippingFee(SELLER_ORDER_ID, ReturnRequestStatus.COMPLETED);
        assertThat(sellerOrder.getOrder().getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("전부 반품이면 SellerOrderReturned: 판매자 부담금 = 반품 배송비 합 + 원래 배송비")
    void allReturnedPublishesSellerOrderReturned() {
        SellerOrder sellerOrder = sellerOrder(OrderItemStatus.RETURNED, OrderItemStatus.RETURNED);
        returnShippingFees("6000");

        completion.complete(sellerOrder.getOrder(), sellerOrder);

        SellerOrderReturnedEvent event = capturedReturned();
        assertThat(event.sellerOrderId()).isEqualTo(SELLER_ORDER_ID);
        assertThat(event.sellerId()).isEqualTo(20L);
        assertThat(event.sellerBurdenAmount()).isEqualByComparingTo("9000"); // 6,000 + 배송비 3,000
        assertThat(event.returnedAt()).isEqualTo(sellerOrder.getConfirmedAt());
        assertThat(event.itemSummary()).isEqualTo("상품1 외 1건");
        verify(purchaseConfirmedEventPublisher, never()).publishAfterCommit(any());
        assertThat(sellerOrder.getConfirmedAt()).isNotNull(); // 종료 기록
        assertThat(sellerOrder.getOrder().getStatus()).isEqualTo(OrderStatus.COMPLETED); // 정산 이벤트가 있으므로 취소 아님
    }

    @Test
    @DisplayName("수거 전 일부 취소 + 나머지 반품도 전부 반품: SellerOrderReturned, 반품된 상품만 요약")
    void cancelledAndReturnedPublishesSellerOrderReturned() {
        SellerOrder sellerOrder = sellerOrder(OrderItemStatus.CANCELLED, OrderItemStatus.RETURNED);
        returnShippingFees("3000");

        completion.complete(sellerOrder.getOrder(), sellerOrder);

        SellerOrderReturnedEvent event = capturedReturned();
        assertThat(event.sellerBurdenAmount()).isEqualByComparingTo("6000"); // 3,000 + 배송비 3,000
        assertThat(event.itemSummary()).isEqualTo("상품2");
        verify(purchaseConfirmedEventPublisher, never()).publishAfterCommit(any());
        assertThat(sellerOrder.getOrder().getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("모두 취소면 정산 이벤트를 보내지 않고 주문을 취소한다")
    void allCancelledPublishesNothing() {
        SellerOrder sellerOrder = sellerOrder(OrderItemStatus.CANCELLED, OrderItemStatus.CANCELLED);

        completion.complete(sellerOrder.getOrder(), sellerOrder);

        verify(purchaseConfirmedEventPublisher, never()).publishAfterCommit(any());
        verify(sellerOrderEventPublisher, never()).publishAfterCommit(any(SellerOrderReturnedEvent.class));
        assertThat(sellerOrder.getConfirmedAt()).isNull();
        assertThat(sellerOrder.getOrder().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("같은 판매자 주문에 종료 판정을 두 번 불러도 정산 이벤트는 한 번만 나간다")
    void publishesOnlyOnce() {
        SellerOrder confirmedOrder = sellerOrder(OrderItemStatus.CONFIRMED, OrderItemStatus.RETURNED);
        SellerOrder returnedOrder = sellerOrder(OrderItemStatus.RETURNED);
        returnShippingFees("3000");

        completion.complete(confirmedOrder.getOrder(), confirmedOrder);
        // 주문은 이미 완료됐지만, 판정이 다시 불려도 정산 이벤트는 다시 나가지 않아야 한다
        ReflectionTestUtils.setField(confirmedOrder.getOrder(), "status", OrderStatus.PAID);
        completion.complete(confirmedOrder.getOrder(), confirmedOrder);
        completion.complete(returnedOrder.getOrder(), returnedOrder);
        ReflectionTestUtils.setField(returnedOrder.getOrder(), "status", OrderStatus.PAID);
        completion.complete(returnedOrder.getOrder(), returnedOrder);

        verify(purchaseConfirmedEventPublisher, times(1)).publishAfterCommit(any());
        verify(sellerOrderEventPublisher, times(1)).publishAfterCommit(any(SellerOrderReturnedEvent.class));
    }

    @ParameterizedTest
    @EnumSource(value = OrderItemStatus.class, names = {"ACCEPT_PENDING", "NORMAL", "CANCEL_PROCESSING",
            "REVIEW_REQUIRED", "RETURN_REQUESTED", "RETURN_IN_TRANSIT", "RETURN_PROCESSING"})
    @DisplayName("끝나지 않은 주문상품이 남으면 정산 이벤트도 주문 상태 변경도 없다")
    void unfinishedItemBlocks(OrderItemStatus unfinished) {
        SellerOrder sellerOrder = sellerOrder(OrderItemStatus.CONFIRMED, unfinished);

        completion.complete(sellerOrder.getOrder(), sellerOrder);

        verify(purchaseConfirmedEventPublisher, never()).publishAfterCommit(any());
        verify(sellerOrderEventPublisher, never()).publishAfterCommit(any(SellerOrderReturnedEvent.class));
        assertThat(sellerOrder.getConfirmedAt()).isNull();
        assertThat(sellerOrder.getOrder().getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("다른 판매자 주문이 전부 취소이고 이 판매자 주문이 전부 반품이면 주문은 완료")
    void otherSellerOrderCancelledAndThisReturnedCompletesOrder() {
        SellerOrder sellerOrder = sellerOrder(OrderItemStatus.RETURNED);
        Orders order = sellerOrder.getOrder();
        SellerOrder cancelled = new SellerOrder(order, 30L);
        OrdersItem cancelledItem = new OrdersItem(cancelled, 900L, "취소", new BigDecimal("5000"));
        cancelled.addItems(List.of(cancelledItem));
        ReflectionTestUtils.setField(cancelledItem, "status", OrderItemStatus.CANCELLED);
        order.addSellerOrders(List.of(cancelled));
        returnShippingFees("3000");

        completion.complete(order, sellerOrder);

        capturedReturned();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    private SellerOrderReturnedEvent capturedReturned() {
        ArgumentCaptor<SellerOrderReturnedEvent> event = ArgumentCaptor.forClass(SellerOrderReturnedEvent.class);
        verify(sellerOrderEventPublisher).publishAfterCommit(event.capture());
        return event.getValue();
    }

    private void returnShippingFees(String amount) {
        when(returnRequestRepository.sumReturnShippingFee(SELLER_ORDER_ID, ReturnRequestStatus.COMPLETED))
                .thenReturn(new BigDecimal(amount));
    }

    // 결제 완료 주문의 판매자 주문 1건(배송비 3,000원). 주문상품 i번째는 「상품i」, 가격 10,000 × i원
    private SellerOrder sellerOrder(OrderItemStatus... statuses) {
        Orders order = new Orders("ORD-" + System.nanoTime(), 10L, "구매자", "010", "12345", "서울", null, statuses.length);
        order.paid();
        SellerOrder sellerOrder = new SellerOrder(order, 20L);
        List<OrdersItem> items = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            items.add(new OrdersItem(sellerOrder, 100L + i, "상품" + (i + 1), BigDecimal.valueOf(10_000L * (i + 1))));
        }
        sellerOrder.addItems(items);
        sellerOrder.setShippingFee(new BigDecimal("3000"));
        for (int i = 0; i < statuses.length; i++) {
            ReflectionTestUtils.setField(items.get(i), "status", statuses[i]);
        }
        order.addSellerOrders(new ArrayList<>(List.of(sellerOrder)));
        ReflectionTestUtils.setField(sellerOrder, "id", SELLER_ORDER_ID);
        return sellerOrder;
    }
}
