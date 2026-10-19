package com.justcommit.backend.notification.infrastructure.messaging;

import com.justcommit.backend.market.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.common.event.PurchaseConfirmedEvent;
import com.justcommit.backend.market.SellerOrderCancelledEvent;
import com.justcommit.backend.market.SellerOrderPaidEvent;
import com.justcommit.backend.market.ShipmentStatusChangedEvent;
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
        String eventKey = "SHIPMENT:" + event.shipmentId() + ":" + event.status();
        switch (event.status()) {
            case "BOOKED" -> notificationService.create(
                    event.sellerId(), NotificationType.SHIPMENT_BOOKED, event.sellerOrderId(), eventKey);
            case "PICKED_UP" -> notificationService.create(
                    event.buyerId(), NotificationType.SHIPMENT_PICKED_UP, event.sellerOrderId(), eventKey);
            case "IN_TRANSIT" -> notificationService.create(
                    event.buyerId(), NotificationType.SHIPMENT_IN_TRANSIT, event.sellerOrderId(), eventKey);
            case "DELIVERED" -> notificationService.create(
                    event.buyerId(), NotificationType.SHIPMENT_DELIVERED, event.sellerOrderId(), eventKey);
            default -> log.info("알림 대상이 아닌 배송 상태: shipmentId={}, status={}", event.shipmentId(), event.status());
        }
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

    @KafkaListener(topics = "${custom.kafka.topics.seller-order-cancelled}", groupId = GROUP_ID)
    public void onSellerOrderCancelled(SellerOrderCancelledEvent event) {
        notificationService.create(event.buyerId(), NotificationType.SELLER_ORDER_CANCELLED_BUYER,
                event.sellerOrderId(), "SELLER_ORDER_CANCELLED:BUYER:" + event.sellerOrderId());
        notificationService.create(event.sellerId(), NotificationType.SELLER_ORDER_CANCELLED,
                event.sellerOrderId(), "SELLER_ORDER_CANCELLED:SELLER:" + event.sellerOrderId());
    }

    @KafkaListener(topics = "${custom.kafka.topics.order-item-auto-confirmed}", groupId = GROUP_ID)
    public void onOrderItemAutoConfirmed(OrderItemAutoConfirmedEvent event) {
        notificationService.create(event.buyerId(), NotificationType.ORDER_ITEM_AUTO_CONFIRMED,
                event.sellerOrderId(), "ORDER_ITEM_AUTO_CONFIRMED:" + event.orderItemId());
    }
}
