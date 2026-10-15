package com.justcommit.backend.market.order.application;

import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.PurchaseConfirmedEvent;
import com.justcommit.backend.market.ShipmentResult;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.PurchaseConfirmedEventPublisher;
import com.justcommit.backend.market.shipment.application.ShipmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderConfirmationPolicyTest {

    @Mock OrdersItemRepository ordersItemRepository;
    @Mock OrdersRepository ordersRepository;
    @Mock SellerOrderRepository sellerOrderRepository;
    @Mock ShipmentService shipmentService;
    @Mock PurchaseConfirmedEventPublisher eventPublisher;
    @InjectMocks OrderItemConfirmationService confirmationService;

    @Test
    void refundRequestedItemBlocksSettlementAfterAnotherItemIsConfirmed() {
        Fixture fixture = fixture();
        ReflectionTestUtils.setField(fixture.second(), "status", OrderItemStatus.REFUND_REQUESTED);
        stubDelivered(fixture, fixture.first());

        confirmationService.confirm(10L, 11L);

        assertThat(fixture.first().getStatus()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(fixture.sellerOrder().getConfirmedAt()).isNull();
        assertThat(fixture.sellerOrder().getOrder().getStatus()).isEqualTo(OrderStatus.PAID);
        verify(eventPublisher, never()).publishAfterCommit(any());
    }

    @Test
    void allCancelledItemsMakeTheOrderCancelledWithoutSettlement() {
        Fixture fixture = fixture();
        fixture.first().cancel();
        fixture.second().cancel();
        fixture.sellerOrder().calculateSettlementBaseAmount();

        ReflectionTestUtils.invokeMethod(confirmationService, "updateOrderStatus", fixture.sellerOrder().getOrder());

        assertThat(fixture.sellerOrder().getSettlementBaseAmount()).isEqualByComparingTo("0");
        assertThat(fixture.sellerOrder().getOrder().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(eventPublisher, never()).publishAfterCommit(any());
    }

    @Test
    void orderCompletesOnlyAfterBothSellerOrdersAreConfirmed() {
        Orders order = new Orders("ORD-two-sellers", 10L, "구매자", "010", "12345", "서울", null, 2);
        order.paid();
        SellerOrder firstSeller = new SellerOrder(order, 20L);
        OrdersItem first = new OrdersItem(firstSeller, 101L, "첫 번째", new BigDecimal("10000"));
        firstSeller.addItems(List.of(first));
        SellerOrder secondSeller = new SellerOrder(order, 30L);
        OrdersItem second = new OrdersItem(secondSeller, 102L, "두 번째", new BigDecimal("20000"));
        secondSeller.addItems(List.of(second));
        order.addSellerOrders(List.of(firstSeller, secondSeller));
        ReflectionTestUtils.setField(firstSeller, "id", 7L);
        ReflectionTestUtils.setField(secondSeller, "id", 8L);
        ReflectionTestUtils.setField(first, "id", 11L);
        ReflectionTestUtils.setField(second, "id", 12L);
        when(ordersItemRepository.findOrderIdByItemId(11L)).thenReturn(Optional.of(99L));
        when(ordersItemRepository.findOrderIdByItemId(12L)).thenReturn(Optional.of(99L));
        when(ordersRepository.findLockedById(99L)).thenReturn(Optional.of(order));
        when(ordersItemRepository.findSellerOrderIdByItemId(11L)).thenReturn(Optional.of(7L));
        when(ordersItemRepository.findSellerOrderIdByItemId(12L)).thenReturn(Optional.of(8L));
        when(sellerOrderRepository.findLockedById(7L)).thenReturn(Optional.of(firstSeller));
        when(sellerOrderRepository.findLockedById(8L)).thenReturn(Optional.of(secondSeller));
        when(ordersItemRepository.findById(11L)).thenReturn(Optional.of(first));
        when(ordersItemRepository.findById(12L)).thenReturn(Optional.of(second));
        when(shipmentService.findBySellerOrderId(7L))
                .thenReturn(new ShipmentResult(31L, "DELIVERED", LocalDateTime.now()));
        when(shipmentService.findBySellerOrderId(8L))
                .thenReturn(new ShipmentResult(32L, "DELIVERED", LocalDateTime.now()));

        confirmationService.confirm(10L, 11L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);

        confirmationService.confirm(10L, 12L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(eventPublisher, times(2)).publishAfterCommit(any(PurchaseConfirmedEvent.class));
    }

    @Test
    void cancelledItemIsExcludedFromSettlementWithoutChangingOriginalAmounts() {
        Fixture fixture = fixture();
        fixture.second().cancel();
        fixture.sellerOrder().calculateSettlementBaseAmount();
        stubDelivered(fixture, fixture.first());

        confirmationService.confirm(10L, 11L);

        assertSettlementUsesRemainingProductAmount(fixture);
    }

    @Test
    void refundedItemIsExcludedFromSettlementWithoutChangingOriginalAmounts() {
        Fixture fixture = fixture();
        ReflectionTestUtils.setField(fixture.second(), "status", OrderItemStatus.REFUNDED);
        fixture.sellerOrder().calculateSettlementBaseAmount();
        stubDelivered(fixture, fixture.first());

        confirmationService.confirm(10L, 11L);

        assertSettlementUsesRemainingProductAmount(fixture);
    }

    private void assertSettlementUsesRemainingProductAmount(Fixture fixture) {
        assertThat(fixture.sellerOrder().getTotalAmount()).isEqualByComparingTo("30000");
        assertThat(fixture.sellerOrder().getShippingFee()).isEqualByComparingTo("3000");
        assertThat(fixture.sellerOrder().getSettlementBaseAmount()).isEqualByComparingTo("10000");
        assertThat(fixture.sellerOrder().getConfirmedAt()).isNotNull();
        assertThat(fixture.sellerOrder().getOrder().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        ArgumentCaptor<PurchaseConfirmedEvent> event = ArgumentCaptor.forClass(PurchaseConfirmedEvent.class);
        verify(eventPublisher, times(1)).publishAfterCommit(event.capture());
        assertThat(event.getValue().saleAmount()).isEqualByComparingTo("10000");
        assertThat(event.getValue().sellerOrderId()).isEqualTo(7L);
    }

    private void stubDelivered(Fixture fixture, OrdersItem item) {
        when(ordersItemRepository.findOrderIdByItemId(item.getId())).thenReturn(Optional.of(99L));
        when(ordersRepository.findLockedById(99L)).thenReturn(Optional.of(fixture.sellerOrder().getOrder()));
        when(ordersItemRepository.findSellerOrderIdByItemId(item.getId())).thenReturn(Optional.of(7L));
        when(sellerOrderRepository.findLockedById(7L)).thenReturn(Optional.of(fixture.sellerOrder()));
        when(ordersItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(shipmentService.findBySellerOrderId(7L))
                .thenReturn(new ShipmentResult(8L, "DELIVERED", LocalDateTime.now()));
    }

    private Fixture fixture() {
        Orders order = new Orders("ORD-test", 10L, "구매자", "010", "12345", "서울", null, 2);
        order.paid();
        SellerOrder sellerOrder = new SellerOrder(order, 20L);
        OrdersItem first = new OrdersItem(sellerOrder, 101L, "첫 번째", new BigDecimal("10000"));
        OrdersItem second = new OrdersItem(sellerOrder, 102L, "두 번째", new BigDecimal("20000"));
        sellerOrder.addItems(List.of(first, second));
        sellerOrder.setShippingFee(new BigDecimal("3000"));
        order.addSellerOrders(List.of(sellerOrder));
        ReflectionTestUtils.setField(sellerOrder, "id", 7L);
        ReflectionTestUtils.setField(first, "id", 11L);
        ReflectionTestUtils.setField(second, "id", 12L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        return new Fixture(sellerOrder, first, second);
    }

    private record Fixture(SellerOrder sellerOrder, OrdersItem first, OrdersItem second) {
    }
}
