package com.justcommit.backend.market.order.infrastructure.kafka;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.PurchaseConfirmedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PurchaseConfirmedEventPublisherTest {

    @Mock OutboxPublisher outboxPublisher;

    @Test
    void delegatesToOutboxPublisher() {
        PurchaseConfirmedEvent event = new PurchaseConfirmedEvent(
                7L, 20L, new BigDecimal("10000"), LocalDateTime.of(2026, 10, 14, 12, 0), "몬스테라");
        PurchaseConfirmedEventPublisher publisher = new PurchaseConfirmedEventPublisher(outboxPublisher);

        publisher.publishAfterCommit(event);

        verify(outboxPublisher).publish(event);
    }
}
