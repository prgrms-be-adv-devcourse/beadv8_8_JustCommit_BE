package com.justcommit.backend.notification.infrastructure.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.notification.infrastructure.messaging.event.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.ImageReviewDelayedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.OrderItemCancelledEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.ProductApprovedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.ProductRejectedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.PurchaseConfirmedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.SellerOrderAcceptedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.SellerOrderCancelledEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.SellerOrderPaidEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.SellerOrderRejectReason;
import com.justcommit.backend.notification.infrastructure.messaging.event.SellerOrderRejectedEvent;
import com.justcommit.backend.notification.infrastructure.messaging.event.ShipmentStatusChangedEvent;
import com.justcommit.backend.notification.application.NotificationService;
import com.justcommit.backend.notification.domain.NotificationType;
import com.justcommit.backend.notification.domain.RecipientRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 다른 서비스의 이벤트를 받아 누구에게 어떤 알림을 보낼지 정한다.
// 서비스마다 토픽 하나(봉투)로 오고, 봉투의 eventType으로 처리할 메서드를 고른다 (docs/conventions/events.md, #561)
// 새 알림을 붙이려면: 아래 switch에 eventType 한 줄 → payload record · 처리 메서드 추가 → NotificationType에 문구 추가
// 처리 실패는 공통 설정이 1 · 2 · 4초 재시도 후 원래토픽.DLT로 보낸다. 같은 이벤트가 다시 와도 event_key로 1건만 만든다
// Consumer Group은 application.yml의 notification
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;
    private final EventEnvelopeMapper envelopeMapper;

    @KafkaListener(topics = KafkaTopics.MARKET_EVENTS)
    public void onMarketEvent(EventEnvelope envelope) {
        switch (envelope.eventType()) {
            case "SellerOrderPaid" -> onSellerOrderPaid(read(envelope, SellerOrderPaidEvent.class));
            // 수락 · 거절은 market #288이 발행하면 바로 받는다 (그 전에는 오지 않음)
            case "SellerOrderAccepted" -> onSellerOrderAccepted(read(envelope, SellerOrderAcceptedEvent.class));
            case "SellerOrderRejected" -> onSellerOrderRejected(read(envelope, SellerOrderRejectedEvent.class));
            case "SellerOrderCancelled" -> onSellerOrderCancelled(read(envelope, SellerOrderCancelledEvent.class));
            case "OrderItemCancelled" -> onOrderItemCancelled(read(envelope, OrderItemCancelledEvent.class));
            case "PurchaseConfirmed" -> onPurchaseConfirmed(read(envelope, PurchaseConfirmedEvent.class));
            case "OrderItemAutoConfirmed" -> onOrderItemAutoConfirmed(read(envelope, OrderItemAutoConfirmedEvent.class));
            case "ShipmentStatusChanged" -> onShipmentStatusChanged(read(envelope, ShipmentStatusChangedEvent.class));
            default -> skip(envelope);
        }
    }

    @KafkaListener(topics = KafkaTopics.PRODUCT_EVENTS)
    public void onProductEvent(EventEnvelope envelope) {
        switch (envelope.eventType()) {
            case "ProductApproved" -> onProductApproved(read(envelope, ProductApprovedEvent.class));
            case "ProductRejected" -> onProductRejected(read(envelope, ProductRejectedEvent.class));
            case "ImageReviewDelayed" ->
                    onImageReviewDelayed(read(envelope, ImageReviewDelayedEvent.class), envelope.eventId());
            // 상품 등록 · 수정 · 상태 동기화(색인용)는 알림과 상관없어 건너뜀
            default -> skip(envelope);
        }
    }

    private <T> T read(EventEnvelope envelope, Class<T> type) {
        return envelopeMapper.readPayload(envelope, type);
    }

    private static void skip(EventEnvelope envelope) {
        log.debug("알림을 만들지 않는 이벤트라 건너뜀: eventType={}, eventId={}", envelope.eventType(), envelope.eventId());
    }

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

    public void onPurchaseConfirmed(PurchaseConfirmedEvent event) {
        notificationService.create(event.sellerId(), NotificationType.PURCHASE_CONFIRMED, event.sellerOrderId(),
                "PURCHASE_CONFIRMED:" + event.sellerOrderId());
    }

    public void onSellerOrderPaid(SellerOrderPaidEvent event) {
        notificationService.create(event.sellerId(), NotificationType.SELLER_ORDER_PAID, event.sellerOrderId(),
                "SELLER_ORDER_PAID:" + event.sellerOrderId());
    }

    // 수락: 구매자에게 1건. 일부 상품을 거절했으면 수락 알림 대신 일부 거절 알림 1건으로 보낸다
    public void onSellerOrderAccepted(SellerOrderAcceptedEvent event) {
        NotificationType type = event.rejectedItemCount() > 0
                ? NotificationType.SELLER_ORDER_PARTLY_ACCEPTED
                : NotificationType.SELLER_ORDER_ACCEPTED;
        notificationService.create(event.buyerId(), type, event.orderId(), event.sellerOrderId(),
                "SELLER_ORDER_ACCEPTED:" + event.sellerOrderId());
    }

    // 구매자 본인이 취소한 것이라 판매자에게만 알린다 (본인이 한 일은 알리지 않음)
    public void onSellerOrderCancelled(SellerOrderCancelledEvent event) {
        notificationService.create(event.sellerId(), NotificationType.SELLER_ORDER_CANCELLED,
                event.sellerOrderId(), "SELLER_ORDER_CANCELLED:SELLER:" + event.sellerOrderId());
    }

    // 전부 거절: 직접 거절이면 구매자에게만, 24시간 자동 거절이면 구매자(자동 취소)와 판매자(자동 거절)에게
    // reason이 없으면 직접 거절로 본다. 한 판매자 주문은 한 번만 거절되므로 키는 받는 사람별로만 나눈다
    public void onSellerOrderRejected(SellerOrderRejectedEvent event) {
        String buyerKey = "SELLER_ORDER_REJECTED:BUYER:" + event.sellerOrderId();
        if (event.reason() == SellerOrderRejectReason.AUTO) {
            notificationService.create(event.buyerId(), NotificationType.SELLER_ORDER_AUTO_CANCELLED,
                    event.orderId(), event.sellerOrderId(), buyerKey);
            notificationService.create(event.sellerId(), NotificationType.SELLER_ORDER_AUTO_REJECTED,
                    event.sellerOrderId(), "SELLER_ORDER_REJECTED:SELLER:" + event.sellerOrderId());
            return;
        }
        notificationService.create(event.buyerId(), NotificationType.SELLER_ORDER_REJECTED,
                event.orderId(), event.sellerOrderId(), buyerKey);
    }

    public void onOrderItemAutoConfirmed(OrderItemAutoConfirmedEvent event) {
        notificationService.create(event.buyerId(), NotificationType.ORDER_ITEM_AUTO_CONFIRMED,
                event.orderId(), event.sellerOrderId(), "ORDER_ITEM_AUTO_CONFIRMED:" + event.orderItemId());
    }

    // 구매자가 상품 일부만 취소: 판매자에게만 알린다. 상품 요약 칸에 취소된 상품명을 둔다
    public void onOrderItemCancelled(OrderItemCancelledEvent event) {
        notificationService.create(event.sellerId(), NotificationType.SELLER_ORDER_ITEM_CANCELLED, null,
                event.sellerOrderId(), event.productName(), "ORDER_ITEM_CANCELLED:SELLER:" + event.orderItemId());
    }

    // 검수 통과 · 반려: 판매자에게. 다시 검수하면 reviewVersion이 달라 새 알림이 된다
    public void onProductApproved(ProductApprovedEvent event) {
        notificationService.createForProduct(event.sellerId(), NotificationType.PRODUCT_APPROVED, null,
                event.productId(), event.productTitle(),
                "PRODUCT_APPROVED:" + event.productId() + ":" + event.reviewVersion());
    }

    public void onProductRejected(ProductRejectedEvent event) {
        notificationService.createForProduct(event.sellerId(), NotificationType.PRODUCT_REJECTED,
                event.reasonMessage(), event.productId(), event.productTitle(),
                "PRODUCT_REJECTED:" + event.productId() + ":" + event.reviewVersion());
    }

    // 관리자 검수 지연: 관리자 목록을 묻지 않고 ADMIN 역할 알림 1건 (#561, #509). 반복 알림 없음
    // product가 매번 새로 늦어진 건만 묶어 보내므로 이벤트마다 새 알림. Outbox 재전송으로 같은 eventId가 다시 오면 1건만
    public void onImageReviewDelayed(ImageReviewDelayedEvent event, String eventId) {
        notificationService.createForRole(RecipientRole.ADMIN, NotificationType.ADMIN_REVIEW_DELAYED,
                NotificationType.ADMIN_REVIEW_DELAYED.getMessage().formatted(event.delayedCount()),
                "ADMIN_REVIEW_DELAYED:" + eventId);
    }
}
