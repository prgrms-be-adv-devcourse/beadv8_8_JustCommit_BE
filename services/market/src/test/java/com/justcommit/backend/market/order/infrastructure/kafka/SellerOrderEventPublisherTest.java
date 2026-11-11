package com.justcommit.backend.market.order.infrastructure.kafka;

import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.market.OrderItemCancelledEvent;
import com.justcommit.backend.market.SellerOrderCancelledEvent;
import com.justcommit.backend.market.SellerOrderPaidEvent;
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
}
