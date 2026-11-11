package com.justcommit.backend.market.order.infrastructure.kafka;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.PurchaseConfirmedEvent;
import org.springframework.stereotype.Component;

@Component
public class PurchaseConfirmedEventPublisher {

    private final OutboxPublisher outboxPublisher;

    public PurchaseConfirmedEventPublisher(OutboxPublisher outboxPublisher) {
        this.outboxPublisher = outboxPublisher;
    }

    public void publishAfterCommit(PurchaseConfirmedEvent event) {
        outboxPublisher.publish(event);
    }
}
