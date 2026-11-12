package com.justcommit.backend.market.order.infrastructure.kafka;

import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.PurchaseConfirmedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PurchaseConfirmedEventPublisherTest {

    private final OutboxPublisher outboxPublisher = mock(OutboxPublisher.class);
    private final PurchaseConfirmedEventPublisher publisher = new PurchaseConfirmedEventPublisher(outboxPublisher);

    @Test
    @DisplayName("구매 확정 이벤트를 market.events.v1 · Key sellerOrderId로 Outbox에 기록한다 (배송비 포함)")
    void publishesToOutbox() {
        PurchaseConfirmedEvent event = new PurchaseConfirmedEvent(8L, 21L, new BigDecimal("10000"),
                new BigDecimal("3000"), LocalDateTime.of(2026, 11, 11, 10, 0), "몬스테라");

        publisher.publishAfterCommit(event);

        verify(outboxPublisher).publish(event);
        assertThat(event.topic()).isEqualTo(KafkaTopics.MARKET_EVENTS);
        assertThat(event.eventType()).isEqualTo("PurchaseConfirmed");
        assertThat(event.aggregateId()).isEqualTo("8");
        assertThat(event.shippingFee()).isEqualByComparingTo("3000");
    }
}
