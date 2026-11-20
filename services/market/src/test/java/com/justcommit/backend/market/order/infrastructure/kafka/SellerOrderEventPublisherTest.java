package com.justcommit.backend.market.order.infrastructure.kafka;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.market.OrderItemCancelledEvent;
import com.justcommit.backend.market.SellerOrderAcceptedEvent;
import com.justcommit.backend.market.SellerOrderCancelledEvent;
import com.justcommit.backend.market.SellerOrderPaidEvent;
import com.justcommit.backend.market.SellerOrderRejectReason;
import com.justcommit.backend.market.SellerOrderRejectedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SellerOrderEventPublisherTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 11, 10, 0);

    private final OutboxPublisher outboxPublisher = mock(OutboxPublisher.class);
    private final SellerOrderEventPublisher publisher = new SellerOrderEventPublisher(outboxPublisher);
    private final EventEnvelopeMapper mapper = new EventEnvelopeMapper();

    @Test
    @DisplayName("판매자 주문 알림 이벤트 3개를 market.events.v1 · Key sellerOrderId로 Outbox에 기록한다")
    void publishesToOutbox() {
        SellerOrderPaidEvent paid = new SellerOrderPaidEvent(8L, 21L, 10L, NOW);
        SellerOrderCancelledEvent cancelled = new SellerOrderCancelledEvent(9L, 8L, 21L, 10L, NOW);
        OrderItemAutoConfirmedEvent autoConfirmed = new OrderItemAutoConfirmedEvent(11L, 9L, 8L, 10L, NOW);

        publisher.publishAfterCommit(paid);
        publisher.publishAfterCommit(cancelled);
        publisher.publishAfterCommit(autoConfirmed);
        OrderItemCancelledEvent itemCancelled = new OrderItemCancelledEvent(11L, 9L, 8L, 21L, "몬스테라", NOW);
        publisher.publishAfterCommit(itemCancelled);

        verify(outboxPublisher).publish(paid);
        verify(outboxPublisher).publish(cancelled);
        verify(outboxPublisher).publish(autoConfirmed);
        verify(outboxPublisher).publish(itemCancelled);
        assertThat(itemCancelled.eventType()).isEqualTo("OrderItemCancelled");
        assertThat(itemCancelled.topic()).isEqualTo(KafkaTopics.MARKET_EVENTS);
        assertThat(paid.eventType()).isEqualTo("SellerOrderPaid");
        assertThat(cancelled.eventType()).isEqualTo("SellerOrderCancelled");
        assertThat(autoConfirmed.eventType()).isEqualTo("OrderItemAutoConfirmed");
        assertThat(paid.topic()).isEqualTo(KafkaTopics.MARKET_EVENTS);
        assertThat(cancelled.aggregateId()).isEqualTo("8");
    }

    @Test
    @DisplayName("수락, 거절 이벤트를 market.events.v1 · Key sellerOrderId로 Outbox에 기록한다 (#288)")
    void publishesDecisionEventsToOutbox() {
        SellerOrderAcceptedEvent accepted = new SellerOrderAcceptedEvent(9L, 8L, 21L, 10L, 1, NOW);
        SellerOrderRejectedEvent rejected = new SellerOrderRejectedEvent(9L, 8L, 21L, 10L,
                SellerOrderRejectReason.AUTO, NOW);

        publisher.publishAfterCommit(accepted);
        publisher.publishAfterCommit(rejected);

        verify(outboxPublisher).publish(accepted);
        verify(outboxPublisher).publish(rejected);
        assertThat(accepted.topic()).isEqualTo(KafkaTopics.MARKET_EVENTS);
        assertThat(rejected.topic()).isEqualTo(KafkaTopics.MARKET_EVENTS);
        assertThat(accepted.aggregateId()).isEqualTo("8");
        assertThat(rejected.aggregateId()).isEqualTo("8");
    }

    @Test
    @DisplayName("수락 이벤트 봉투를 알림 쪽 record 모양으로 읽으면 모든 값이 같고 eventType은 SellerOrderAccepted")
    void acceptedPayloadMatchesNotificationContract() {
        SellerOrderAcceptedEvent event = new SellerOrderAcceptedEvent(9L, 8L, 21L, 10L, 2, NOW);

        EventEnvelope envelope = roundTrip(event);

        assertThat(envelope.eventType()).isEqualTo("SellerOrderAccepted");
        assertThat(mapper.readPayload(envelope, NotificationSellerOrderAccepted.class))
                .isEqualTo(new NotificationSellerOrderAccepted(9L, 8L, 21L, 10L, 2, NOW));
    }

    @Test
    @DisplayName("거절 이벤트 봉투를 알림 쪽 record 모양으로 읽으면 모든 값이 같고 reason은 SELLER, AUTO 이름 문자열")
    void rejectedPayloadMatchesNotificationContract() {
        for (SellerOrderRejectReason reason : SellerOrderRejectReason.values()) {
            SellerOrderRejectedEvent event = new SellerOrderRejectedEvent(9L, 8L, 21L, 10L, reason, NOW);

            EventEnvelope envelope = roundTrip(event);

            assertThat(envelope.eventType()).isEqualTo("SellerOrderRejected");
            assertThat(envelope.payload().get("reason").asString()).isEqualTo(reason.name());
            assertThat(mapper.readPayload(envelope, NotificationSellerOrderRejected.class))
                    .isEqualTo(new NotificationSellerOrderRejected(9L, 8L, 21L, 10L,
                            NotificationRejectReason.valueOf(reason.name()), NOW));
        }
    }

    // 실제로 Kafka에 나가는 형태(JSON 문자열)를 거쳐 받는 쪽처럼 다시 읽는다
    private EventEnvelope roundTrip(com.justcommit.backend.common.messaging.IntegrationEvent event) {
        return mapper.fromJson(mapper.toJson(mapper.wrap(event)));
    }

    // notification의 messaging.event.SellerOrderAcceptedEvent와 같은 모양 (필드 이름, 순서, 타입)
    private record NotificationSellerOrderAccepted(Long orderId, Long sellerOrderId, Long sellerId, Long buyerId,
                                                   int rejectedItemCount, LocalDateTime acceptedAt) {
    }

    // notification의 messaging.event.SellerOrderRejectedEvent와 같은 모양
    private record NotificationSellerOrderRejected(Long orderId, Long sellerOrderId, Long sellerId, Long buyerId,
                                                   NotificationRejectReason reason, LocalDateTime rejectedAt) {
    }

    // notification의 messaging.event.SellerOrderRejectReason과 같은 값
    private enum NotificationRejectReason {
        SELLER,
        AUTO
    }
}
