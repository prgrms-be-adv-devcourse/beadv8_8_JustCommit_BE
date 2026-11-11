package com.justcommit.backend.product.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
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
// 봉투 형식 오류는 받는 단계(공통 역직렬화)에서, payload 형식 오류는 readPayload의 Jackson 예외로 재시도 없이 DLT
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketEventListener {

    private final EventEnvelopeMapper mapper;
    private final ProductUseCase productUseCase;

    @KafkaListener(topics = "${MARKET_ORDER_PAID_TOPIC:" + KafkaTopics.MARKET_ORDER_PAID + "}")
    public void onOrderPaid(EventEnvelope envelope) {
        OrderPaid payload = mapper.readPayload(envelope, OrderPaid.class);
        boolean changed = productUseCase.markSoldOut(payload.orderId());
        log.info("판매 완료 처리: eventId={}, orderId={}, changed={}", envelope.eventId(), payload.orderId(), changed);
    }

    @KafkaListener(topics = {
            "${MARKET_ORDER_EXPIRED_TOPIC:" + KafkaTopics.MARKET_ORDER_EXPIRED + "}",
            "${MARKET_ORDER_FAILED_TOPIC:" + KafkaTopics.MARKET_ORDER_FAILED + "}"})
    public void onOrderReleased(EventEnvelope envelope) {
        OrderReleased payload = mapper.readPayload(envelope, OrderReleased.class);
        boolean changed = productUseCase.release(payload.orderId());
        log.info("결제 전 예약 해제: eventId={}, eventType={}, orderId={}, changed={}",
                envelope.eventId(), envelope.eventType(), payload.orderId(), changed);
    }

    @KafkaListener(topics = "${MARKET_ORDER_ITEM_CANCELLED_TOPIC:" + KafkaTopics.MARKET_ORDER_ITEM_CANCELLED + "}")
    public void onOrderItemCancelled(EventEnvelope envelope) {
        OrderItemCancelled payload = mapper.readPayload(envelope, OrderItemCancelled.class);
        boolean changed = productUseCase.releaseItem(payload.productId(), payload.orderId(), payload.hidesProduct());
        log.info("판매 재개(취소): eventId={}, orderId={}, productId={}, reason={}, changed={}",
                envelope.eventId(), payload.orderId(), payload.productId(), payload.reason(), changed);
    }

    @KafkaListener(topics = "${MARKET_ORDER_ITEM_RETURNED_TOPIC:" + KafkaTopics.MARKET_ORDER_ITEM_RETURNED + "}")
    public void onOrderItemReturned(EventEnvelope envelope) {
        OrderItemReturned payload = mapper.readPayload(envelope, OrderItemReturned.class);
        boolean changed = productUseCase.releaseItem(payload.productId(), payload.orderId(), true);
        log.info("판매 재개(반품): eventId={}, orderId={}, productId={}, changed={}",
                envelope.eventId(), payload.orderId(), payload.productId(), changed);
    }
}
