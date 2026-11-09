package com.justcommit.backend.notification.infrastructure.messaging;

import com.justcommit.backend.notification.infrastructure.messaging.event.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.PurchaseConfirmedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.SellerOrderAcceptedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.SellerOrderCancelledEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.SellerOrderPaidEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.ShipmentStatusChangedEvent;
import com.justcommit.backend.notification.application.NotificationService;
import com.justcommit.backend.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 다른 모듈의 이벤트를 받아 누구에게 어떤 알림을 보낼지 정한다.
// 새 알림을 붙이려면: 보내는 모듈이 이벤트를 커밋 후 발행 → 여기에 @KafkaListener 메서드 추가 → NotificationType에 문구 추가
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private static final String GROUP_ID = "notification";

    private final NotificationService notificationService;

    @KafkaListener(topics = "${custom.kafka.topics.shipment-status-changed}", groupId = GROUP_ID)
    public void onShipmentStatusChanged(ShipmentStatusChangedEvent event) {
        // 같은 이벤트로 구매자·판매자 알림을 각각 만들므로 중복 방지 키를 받는 사람별로 나눈다
        String buyerKey = "SHIPMENT:" + event.shipmentId() + ":" + event.status();
        String sellerKey = buyerKey + ":SELLER";
        switch (event.status()) {
            case "BOOKED" -> notifySeller(event, NotificationType.SHIPMENT_BOOKED, buyerKey); // 기존 키 유지
            case "PICKED_UP" -> {
                notifyBuyer(event, NotificationType.SHIPMENT_PICKED_UP, buyerKey);
                notifySeller(event, NotificationType.SELLER_SHIPMENT_PICKED_UP, sellerKey);
            }
            case "IN_TRANSIT" -> {
                notifyBuyer(event, NotificationType.SHIPMENT_IN_TRANSIT, buyerKey);
                notifySeller(event, NotificationType.SELLER_SHIPMENT_IN_TRANSIT, sellerKey);
            }
            case "DELIVERED" -> {
                notifyBuyer(event, NotificationType.SHIPMENT_DELIVERED, buyerKey);
                notifySeller(event, NotificationType.SELLER_SHIPMENT_DELIVERED, sellerKey);
            }
            default -> log.info("알림 대상이 아닌 배송 상태: shipmentId={}, status={}", event.shipmentId(), event.status());
        }
    }

    // 구매자는 주문 상세(orderId)로, 판매자는 판매 주문 상세(sellerOrderId)로 이동한다
    private void notifyBuyer(ShipmentStatusChangedEvent event, NotificationType type, String eventKey) {
        notificationService.create(event.buyerId(), type, event.orderId(), event.sellerOrderId(), event.itemSummary(),
                eventKey);
    }

    private void notifySeller(ShipmentStatusChangedEvent event, NotificationType type, String eventKey) {
        notificationService.create(event.sellerId(), type, null, event.sellerOrderId(), event.itemSummary(), eventKey);
    }

    @KafkaListener(topics = "${custom.kafka.topics.purchase-confirmed}", groupId = GROUP_ID)
    public void onPurchaseConfirmed(PurchaseConfirmedEvent event) {
        notificationService.create(event.sellerId(), NotificationType.PURCHASE_CONFIRMED, event.sellerOrderId(),
                "PURCHASE_CONFIRMED:" + event.sellerOrderId());
    }

    @KafkaListener(topics = "${custom.kafka.topics.seller-order-paid}", groupId = GROUP_ID)
    public void onSellerOrderPaid(SellerOrderPaidEvent event) {
        notificationService.create(event.sellerId(), NotificationType.SELLER_ORDER_PAID, event.sellerOrderId(),
                "SELLER_ORDER_PAID:" + event.sellerOrderId());
    }

    // 수락: 구매자에게 1건. 일부 상품을 거절했으면 수락 알림 대신 일부 거절 알림 1건으로 보낸다
    @KafkaListener(topics = "${custom.kafka.topics.seller-order-accepted}", groupId = GROUP_ID)
    public void onSellerOrderAccepted(SellerOrderAcceptedEvent event) {
        NotificationType type = event.rejectedItemCount() > 0
                ? NotificationType.SELLER_ORDER_PARTLY_ACCEPTED
                : NotificationType.SELLER_ORDER_ACCEPTED;
        notificationService.create(event.buyerId(), type, event.orderId(), event.sellerOrderId(),
                "SELLER_ORDER_ACCEPTED:" + event.sellerOrderId());
    }

    // 취소 이유별로 받는 사람이 다르다. 한 판매자 주문은 한 번만 취소되므로 키는 받는 사람별로만 나눈다
    // BUYER: 판매자에게만 (취소한 구매자 본인에게는 알리지 않음)
    // SELLER: 구매자에게 판매자 거절
    // AUTO: 구매자에게 자동 취소, 판매자에게 24시간 자동 거절
    @KafkaListener(topics = "${custom.kafka.topics.seller-order-cancelled}", groupId = GROUP_ID)
    public void onSellerOrderCancelled(SellerOrderCancelledEvent event) {
        switch (event.reasonOrDefault()) {
            case BUYER -> notifySellerOfCancel(event, NotificationType.SELLER_ORDER_CANCELLED);
            case SELLER -> notifyBuyerOfCancel(event, NotificationType.SELLER_ORDER_REJECTED);
            case AUTO -> {
                notifyBuyerOfCancel(event, NotificationType.SELLER_ORDER_AUTO_CANCELLED);
                notifySellerOfCancel(event, NotificationType.SELLER_ORDER_AUTO_REJECTED);
            }
        }
    }

    private void notifySellerOfCancel(SellerOrderCancelledEvent event, NotificationType type) {
        notificationService.create(event.sellerId(), type, event.sellerOrderId(),
                "SELLER_ORDER_CANCELLED:SELLER:" + event.sellerOrderId());
    }

    private void notifyBuyerOfCancel(SellerOrderCancelledEvent event, NotificationType type) {
        notificationService.create(event.buyerId(), type, event.orderId(), event.sellerOrderId(),
                "SELLER_ORDER_CANCELLED:BUYER:" + event.sellerOrderId());
    }

    @KafkaListener(topics = "${custom.kafka.topics.order-item-auto-confirmed}", groupId = GROUP_ID)
    public void onOrderItemAutoConfirmed(OrderItemAutoConfirmedEvent event) {
        notificationService.create(event.buyerId(), NotificationType.ORDER_ITEM_AUTO_CONFIRMED,
                event.orderId(), event.sellerOrderId(), "ORDER_ITEM_AUTO_CONFIRMED:" + event.orderItemId());
    }
}
