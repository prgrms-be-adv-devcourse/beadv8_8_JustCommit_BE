package com.justcommit.backend.product.messaging;

import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.product.ProductUseCase;
import com.justcommit.backend.product.messaging.event.OrderItemCancelled;
import com.justcommit.backend.product.messaging.event.OrderItemReturned;
import com.justcommit.backend.product.messaging.event.OrderPaid;
import com.justcommit.backend.product.messaging.event.OrderReleased;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// market → product 이벤트 5개: 판매 완료 · 결제 전 예약 해제 · 판매 재개 (ADR 0016)
// 토픽이 이벤트마다 달라 eventType이 아니라 토픽으로 나눈다.
// 토픽끼리는 도착 순서가 바뀔 수 있어, 모든 처리가 "이 주문이 잡은 상품만, 바뀐 상품 0건이어도 정상"이다
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketEventListener {

    private final EventReader eventReader;
    private final ProductUseCase productUseCase;

    @KafkaListener(topics = "${MARKET_ORDER_PAID_TOPIC:" + KafkaTopics.MARKET_ORDER_PAID + "}")
    public void onOrderPaid(String message) {
        ReceivedEvent<OrderPaid> event = eventReader.read(message, OrderPaid.class);
        Long orderId = event.payload().orderId();
        boolean changed = productUseCase.markSoldOut(orderId);
        log.info("판매 완료 처리: eventId={}, orderId={}, changed={}", event.eventId(), orderId, changed);
    }

    @KafkaListener(topics = {
            "${MARKET_ORDER_EXPIRED_TOPIC:" + KafkaTopics.MARKET_ORDER_EXPIRED + "}",
            "${MARKET_ORDER_FAILED_TOPIC:" + KafkaTopics.MARKET_ORDER_FAILED + "}"})
    public void onOrderReleased(String message) {
        ReceivedEvent<OrderReleased> event = eventReader.read(message, OrderReleased.class);
        Long orderId = event.payload().orderId();
        boolean changed = productUseCase.release(orderId);
        log.info("결제 전 예약 해제: eventId={}, eventType={}, orderId={}, changed={}",
                event.eventId(), event.envelope().eventType(), orderId, changed);
    }

    @KafkaListener(topics = "${MARKET_ORDER_ITEM_CANCELLED_TOPIC:" + KafkaTopics.MARKET_ORDER_ITEM_CANCELLED + "}")
    public void onOrderItemCancelled(String message) {
        ReceivedEvent<OrderItemCancelled> event = eventReader.read(message, OrderItemCancelled.class);
        OrderItemCancelled payload = event.payload();
        boolean changed = productUseCase.releaseItem(payload.productId(), payload.orderId(), payload.hidesProduct());
        log.info("판매 재개(취소): eventId={}, orderId={}, productId={}, reason={}, changed={}",
                event.eventId(), payload.orderId(), payload.productId(), payload.reason(), changed);
    }

    @KafkaListener(topics = "${MARKET_ORDER_ITEM_RETURNED_TOPIC:" + KafkaTopics.MARKET_ORDER_ITEM_RETURNED + "}")
    public void onOrderItemReturned(String message) {
        ReceivedEvent<OrderItemReturned> event = eventReader.read(message, OrderItemReturned.class);
        OrderItemReturned payload = event.payload();
        boolean changed = productUseCase.releaseItem(payload.productId(), payload.orderId(), true);
        log.info("판매 재개(반품): eventId={}, orderId={}, productId={}, changed={}",
                event.eventId(), payload.orderId(), payload.productId(), changed);
    }
}
