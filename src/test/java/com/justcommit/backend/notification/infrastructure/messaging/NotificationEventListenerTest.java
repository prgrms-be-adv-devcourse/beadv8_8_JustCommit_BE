package com.justcommit.backend.notification.infrastructure.messaging;

import com.justcommit.backend.common.event.PurchaseConfirmedEvent;
import com.justcommit.backend.market.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.market.SellerOrderCancelledEvent;
import com.justcommit.backend.market.SellerOrderPaidEvent;
import com.justcommit.backend.market.ShipmentStatusChangedEvent;
import com.justcommit.backend.notification.application.NotificationService;
import com.justcommit.backend.notification.domain.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

    private static final Long ORDER_ID = 9L;
    private static final Long SELLER_ORDER_ID = 7L;
    private static final Long BUYER = 10L;
    private static final Long SELLER = 20L;

    @Mock NotificationService notificationService;
    @InjectMocks NotificationEventListener listener;

    @Test
    @DisplayName("택배 예약은 판매자에게만 알린다")
    void notifiesSellerOnBooked() {
        listener.onShipmentStatusChanged(shipmentEvent("BOOKED"));

        verify(notificationService).create(SELLER, NotificationType.SHIPMENT_BOOKED, SELLER_ORDER_ID, "SHIPMENT:1:BOOKED");
        verify(notificationService, never()).create(any(), any(), any(), any(), anyString());
    }

    @ParameterizedTest(name = "{0} → 구매자 {1}, 판매자 {2}")
    @CsvSource({
            "PICKED_UP, SHIPMENT_PICKED_UP, SELLER_SHIPMENT_PICKED_UP",
            "IN_TRANSIT, SHIPMENT_IN_TRANSIT, SELLER_SHIPMENT_IN_TRANSIT",
            "DELIVERED, SHIPMENT_DELIVERED, SELLER_SHIPMENT_DELIVERED"
    })
    @DisplayName("수거·배송 중·배송 완료는 구매자(주문 id 포함)와 판매자에게 각각 다른 키로 알린다")
    void notifiesBuyerAndSeller(String status, NotificationType buyerType, NotificationType sellerType) {
        listener.onShipmentStatusChanged(shipmentEvent(status));

        verify(notificationService).create(BUYER, buyerType, ORDER_ID, SELLER_ORDER_ID, "SHIPMENT:1:" + status);
        verify(notificationService).create(SELLER, sellerType, SELLER_ORDER_ID, "SHIPMENT:1:" + status + ":SELLER");
    }

    @Test
    @DisplayName("알림 대상이 아닌 배송 상태는 알림을 만들지 않는다")
    void ignoresOtherStatus() {
        listener.onShipmentStatusChanged(shipmentEvent("CANCELLED"));

        verify(notificationService, never()).create(any(), any(), any(), anyString());
        verify(notificationService, never()).create(any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("구매확정 이벤트는 판매자에게 알림을 만든다")
    void notifiesSellerOnPurchaseConfirmed() {
        listener.onPurchaseConfirmed(new PurchaseConfirmedEvent(SELLER_ORDER_ID, SELLER, new BigDecimal("10000"),
                LocalDateTime.of(2026, 10, 16, 12, 0)));

        verify(notificationService).create(SELLER, NotificationType.PURCHASE_CONFIRMED, SELLER_ORDER_ID,
                "PURCHASE_CONFIRMED:7");
    }

    @Test
    void notifiesSellerOnPaidOrder() {
        listener.onSellerOrderPaid(new SellerOrderPaidEvent(SELLER_ORDER_ID, SELLER, BUYER, LocalDateTime.now()));

        verify(notificationService).create(SELLER, NotificationType.SELLER_ORDER_PAID, SELLER_ORDER_ID,
                "SELLER_ORDER_PAID:7");
    }

    @Test
    void notifiesBuyerAndSellerWithDifferentKeysOnCancellation() {
        listener.onSellerOrderCancelled(new SellerOrderCancelledEvent(
                ORDER_ID, SELLER_ORDER_ID, SELLER, BUYER, LocalDateTime.now()));

        verify(notificationService).create(BUYER, NotificationType.SELLER_ORDER_CANCELLED_BUYER,
                ORDER_ID, SELLER_ORDER_ID, "SELLER_ORDER_CANCELLED:BUYER:7");
        verify(notificationService).create(SELLER, NotificationType.SELLER_ORDER_CANCELLED,
                SELLER_ORDER_ID, "SELLER_ORDER_CANCELLED:SELLER:7");
    }

    @Test
    void notifiesBuyerForEachAutomaticallyConfirmedItem() {
        listener.onOrderItemAutoConfirmed(new OrderItemAutoConfirmedEvent(11L, ORDER_ID, SELLER_ORDER_ID, BUYER,
                LocalDateTime.now()));

        verify(notificationService).create(BUYER, NotificationType.ORDER_ITEM_AUTO_CONFIRMED,
                ORDER_ID, SELLER_ORDER_ID, "ORDER_ITEM_AUTO_CONFIRMED:11");
    }

    private ShipmentStatusChangedEvent shipmentEvent(String status) {
        return new ShipmentStatusChangedEvent(1L, ORDER_ID, SELLER_ORDER_ID, BUYER, SELLER, status,
                LocalDateTime.of(2026, 10, 16, 10, 0));
    }
}
