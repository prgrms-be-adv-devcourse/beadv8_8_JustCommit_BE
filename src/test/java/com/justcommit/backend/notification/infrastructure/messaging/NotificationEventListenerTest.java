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

    private static final Long SELLER_ORDER_ID = 7L;
    private static final Long BUYER = 10L;
    private static final Long SELLER = 20L;

    @Mock NotificationService notificationService;
    @InjectMocks NotificationEventListener listener;

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "BOOKED, SHIPMENT_BOOKED, 20",      // 판매자
            "PICKED_UP, SHIPMENT_PICKED_UP, 10", // 구매자
            "IN_TRANSIT, SHIPMENT_IN_TRANSIT, 10",
            "DELIVERED, SHIPMENT_DELIVERED, 10"
    })
    @DisplayName("배송 상태별로 받는 사람과 알림 종류를 정한다")
    void mapsShipmentStatus(String status, NotificationType type, Long recipient) {
        listener.onShipmentStatusChanged(shipmentEvent(status));

        verify(notificationService).create(recipient, type, SELLER_ORDER_ID, "SHIPMENT:1:" + status);
    }

    @Test
    @DisplayName("알림 대상이 아닌 배송 상태는 알림을 만들지 않는다")
    void ignoresOtherStatus() {
        listener.onShipmentStatusChanged(shipmentEvent("CANCELLED"));

        verify(notificationService, never()).create(anyLong(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("구매확정 이벤트는 판매자에게 알림을 만든다")
    void notifiesSellerOnPurchaseConfirmed() {
        listener.onPurchaseConfirmed(new PurchaseConfirmedEvent(SELLER_ORDER_ID, SELLER, new BigDecimal("10000"),
                LocalDateTime.of(2026, 10, 16, 12, 0), "몬스테라"));

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
                SELLER_ORDER_ID, SELLER, BUYER, LocalDateTime.now()));

        verify(notificationService).create(BUYER, NotificationType.SELLER_ORDER_CANCELLED_BUYER,
                SELLER_ORDER_ID, "SELLER_ORDER_CANCELLED:BUYER:7");
        verify(notificationService).create(SELLER, NotificationType.SELLER_ORDER_CANCELLED,
                SELLER_ORDER_ID, "SELLER_ORDER_CANCELLED:SELLER:7");
    }

    @Test
    void notifiesBuyerForEachAutomaticallyConfirmedItem() {
        listener.onOrderItemAutoConfirmed(new OrderItemAutoConfirmedEvent(11L, SELLER_ORDER_ID, BUYER,
                LocalDateTime.now()));

        verify(notificationService).create(BUYER, NotificationType.ORDER_ITEM_AUTO_CONFIRMED,
                SELLER_ORDER_ID, "ORDER_ITEM_AUTO_CONFIRMED:11");
    }

    private ShipmentStatusChangedEvent shipmentEvent(String status) {
        return new ShipmentStatusChangedEvent(1L, SELLER_ORDER_ID, BUYER, SELLER, status,
                LocalDateTime.of(2026, 10, 16, 10, 0));
    }
}
