package com.justcommit.backend.notification.infrastructure.messaging;

import com.justcommit.backend.notification.infrastructure.messaging.event.PurchaseConfirmedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.ImageReviewDelayedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.OrderItemCancelledEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.ProductAdminHiddenEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.ProductApprovedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.ProductRejectedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.SellerOrderAcceptedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.SellerOrderCancelledEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.SellerOrderPaidEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.SellerOrderRejectReason;
import com.justcommit.backend.notification.infrastructure.messaging.event.SellerOrderRejectedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.ShipmentStatusChangedEvent;
import com.justcommit.backend.notification.application.NotificationService;
import com.justcommit.backend.notification.domain.NotificationType;
import com.justcommit.backend.notification.domain.RecipientRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

    private static final Long ORDER_ID = 9L;
    private static final String ITEM_SUMMARY = "몬스테라 외 1건";
    private static final Long SELLER_ORDER_ID = 7L;
    private static final Long BUYER = 10L;
    private static final Long SELLER = 20L;

    @Mock NotificationService notificationService;
    @InjectMocks NotificationEventListener listener;

    @Test
    @DisplayName("택배 예약은 판매자에게만 알린다")
    void notifiesSellerOnBooked() {
        listener.onShipmentStatusChanged(shipmentEvent("BOOKED"));

        verify(notificationService).create(SELLER, NotificationType.SHIPMENT_BOOKED, null, SELLER_ORDER_ID, ITEM_SUMMARY,
                "SHIPMENT:1:BOOKED");
        verify(notificationService, never()).create(eq(BUYER), any(), any(), any(), any(), anyString());
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

        // 배송 알림은 이벤트의 상품 요약을 그대로 쓴다 (조회 없음)
        verify(notificationService).create(BUYER, buyerType, ORDER_ID, SELLER_ORDER_ID, ITEM_SUMMARY, "SHIPMENT:1:" + status);
        verify(notificationService).create(SELLER, sellerType, null, SELLER_ORDER_ID, ITEM_SUMMARY,
                "SHIPMENT:1:" + status + ":SELLER");
    }

    @Test
    @DisplayName("알림 대상이 아닌 배송 상태는 알림을 만들지 않는다")
    void ignoresOtherStatus() {
        listener.onShipmentStatusChanged(shipmentEvent("CANCELLED"));

        verify(notificationService, never()).create(any(), any(), any(), anyString());
        verify(notificationService, never()).create(any(), any(), any(), any(), anyString());
        verify(notificationService, never()).create(any(), any(), any(), any(), any(), anyString());
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
    @DisplayName("[S-I01] 구매자 취소는 판매자에게만 알린다")
    void notifiesOnlySellerOnCancellationByBuyer() {
        listener.onSellerOrderCancelled(new SellerOrderCancelledEvent(
                ORDER_ID, SELLER_ORDER_ID, SELLER, BUYER, LocalDateTime.now()));

        verify(notificationService).create(SELLER, NotificationType.SELLER_ORDER_CANCELLED,
                SELLER_ORDER_ID, "SELLER_ORDER_CANCELLED:SELLER:7");
        verifyNoMoreInteractions(notificationService); // 취소한 구매자 본인에게는 알리지 않음
    }

    @ParameterizedTest(name = "reason={0}")
    @NullSource
    @EnumSource(value = SellerOrderRejectReason.class, names = "SELLER")
    @DisplayName("[S-I01] 판매자 직접 거절(reason 없음 포함)은 구매자에게만 판매자 거절 알림을 보낸다")
    void notifiesOnlyBuyerOnRejectionBySeller(SellerOrderRejectReason reason) {
        listener.onSellerOrderRejected(rejectedEvent(reason));

        verify(notificationService).create(BUYER, NotificationType.SELLER_ORDER_REJECTED,
                ORDER_ID, SELLER_ORDER_ID, "SELLER_ORDER_REJECTED:BUYER:7");
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    @DisplayName("24시간 자동 거절은 구매자(자동 취소)와 판매자(자동 거절)에게 각각 다른 키로 알린다")
    void notifiesBuyerAndSellerOnAutoRejection() {
        listener.onSellerOrderRejected(rejectedEvent(SellerOrderRejectReason.AUTO));

        verify(notificationService).create(BUYER, NotificationType.SELLER_ORDER_AUTO_CANCELLED,
                ORDER_ID, SELLER_ORDER_ID, "SELLER_ORDER_REJECTED:BUYER:7");
        verify(notificationService).create(SELLER, NotificationType.SELLER_ORDER_AUTO_REJECTED,
                SELLER_ORDER_ID, "SELLER_ORDER_REJECTED:SELLER:7");
        verifyNoMoreInteractions(notificationService);
    }

    @ParameterizedTest(name = "거절 {0}건 → {1}")
    @CsvSource({
            "0, SELLER_ORDER_ACCEPTED",
            "2, SELLER_ORDER_PARTLY_ACCEPTED"
    })
    @DisplayName("수락은 구매자에게 1건만 알리고, 일부 거절이면 일부 거절 알림으로 보낸다")
    void notifiesBuyerOnAcceptance(int rejectedItemCount, NotificationType type) {
        listener.onSellerOrderAccepted(new SellerOrderAcceptedEvent(ORDER_ID, SELLER_ORDER_ID, SELLER, BUYER,
                rejectedItemCount, LocalDateTime.now()));

        verify(notificationService).create(BUYER, type, ORDER_ID, SELLER_ORDER_ID, "SELLER_ORDER_ACCEPTED:7");
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void notifiesBuyerForEachAutomaticallyConfirmedItem() {
        listener.onOrderItemAutoConfirmed(new OrderItemAutoConfirmedEvent(11L, ORDER_ID, SELLER_ORDER_ID, BUYER,
                LocalDateTime.now()));

        verify(notificationService).create(BUYER, NotificationType.ORDER_ITEM_AUTO_CONFIRMED,
                ORDER_ID, SELLER_ORDER_ID, "ORDER_ITEM_AUTO_CONFIRMED:11");
    }

    @Test
    @DisplayName("[S-I01] 구매자가 상품 일부만 취소하면 판매자에게만, 취소된 상품명을 상품 요약으로 알린다")
    void notifiesOnlySellerOnPartialCancellation() {
        listener.onOrderItemCancelled(new OrderItemCancelledEvent(11L, ORDER_ID, SELLER_ORDER_ID, SELLER,
                "몬스테라", LocalDateTime.now()));

        verify(notificationService).create(SELLER, NotificationType.SELLER_ORDER_ITEM_CANCELLED, null,
                SELLER_ORDER_ID, "몬스테라", "ORDER_ITEM_CANCELLED:SELLER:11");
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    @DisplayName("검수 통과는 판매자에게 상품으로 이동하는 알림을 만들고, 키에 검수 차수를 넣는다")
    void notifiesSellerOnProductApproved() {
        listener.onProductApproved(new ProductApprovedEvent(30L, SELLER, 2, "몬스테라 알보"));

        verify(notificationService).createForProduct(SELLER, NotificationType.PRODUCT_APPROVED, null, 30L,
                "몬스테라 알보", "PRODUCT_APPROVED:30:2");
    }

    @Test
    @DisplayName("검수 반려는 판매자에게 사유를 넘겨 알림을 만든다")
    void notifiesSellerOnProductRejected() {
        listener.onProductRejected(new ProductRejectedEvent(30L, SELLER, 1, "몬스테라 알보", "HARMFUL_IMAGE",
                "사진이 정책에 맞지 않아요."));

        verify(notificationService).createForProduct(SELLER, NotificationType.PRODUCT_REJECTED,
                "사진이 정책에 맞지 않아요.", 30L, "몬스테라 알보", "PRODUCT_REJECTED:30:1");
    }

    @Test
    @DisplayName("관리자 강제 숨김은 판매자에게 사유를 넘겨 알림을 만들고, 키는 상품 · 숨긴 시각 (#623)")
    void notifiesSellerOnProductAdminHidden() {
        listener.onProductAdminHidden(new ProductAdminHiddenEvent(30L, SELLER, "몬스테라 알보", "허위 매물",
                LocalDateTime.of(2026, 11, 21, 15, 0)));

        verify(notificationService).createForProduct(SELLER, NotificationType.PRODUCT_ADMIN_HIDDEN,
                "허위 매물", 30L, "몬스테라 알보", "PRODUCT_ADMIN_HIDDEN:30:2026-11-21T15:00");
    }

    @Test
    @DisplayName("관리자 검수 지연은 건수를 넣은 문구로 ADMIN 역할 알림 1건, 키는 eventId")
    void notifiesAdminsOnImageReviewDelayed() {
        listener.onImageReviewDelayed(new ImageReviewDelayedEvent(3, LocalDateTime.of(2026, 11, 19, 14, 0)), "e-1");

        verify(notificationService).createForRole(RecipientRole.ADMIN, NotificationType.ADMIN_REVIEW_DELAYED,
                "검수 확인이 늦어진 상품이 3건 있어요. 24시간이 지나면 자동 반려돼요.", "ADMIN_REVIEW_DELAYED:e-1");
        verifyNoMoreInteractions(notificationService);
    }

    private SellerOrderRejectedEvent rejectedEvent(SellerOrderRejectReason reason) {
        return new SellerOrderRejectedEvent(ORDER_ID, SELLER_ORDER_ID, SELLER, BUYER, reason, LocalDateTime.now());
    }

    private ShipmentStatusChangedEvent shipmentEvent(String status) {
        return new ShipmentStatusChangedEvent(1L, ORDER_ID, SELLER_ORDER_ID, BUYER, SELLER, status,
                LocalDateTime.of(2026, 10, 16, 10, 0), ITEM_SUMMARY);
    }
}
