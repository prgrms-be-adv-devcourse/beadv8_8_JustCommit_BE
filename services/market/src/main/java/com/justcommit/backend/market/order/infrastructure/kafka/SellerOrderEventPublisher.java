package com.justcommit.backend.market.order.infrastructure.kafka;

import com.justcommit.backend.market.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.OrderFailedEvent;
import com.justcommit.backend.market.OrderExpiredEvent;
import com.justcommit.backend.market.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.market.OrderItemCancelledEvent;
import com.justcommit.backend.market.OrderItemCancelledForProductEvent;
import com.justcommit.backend.market.OrderPaidEvent;
import com.justcommit.backend.market.SellerOrderCancelledEvent;
import com.justcommit.backend.market.SellerOrderPaidEvent;
import org.springframework.stereotype.Component;

@Component
public class SellerOrderEventPublisher {

    private final OutboxPublisher outboxPublisher;

    public SellerOrderEventPublisher(OutboxPublisher outboxPublisher) {
        this.outboxPublisher = outboxPublisher;
    }

    public void publishAfterCommit(SellerOrderPaidEvent event) {
        outboxPublisher.publish(event);
    }

    public void publishAfterCommit(SellerOrderCancelledEvent event) {
        outboxPublisher.publish(event);
    }

    public void publishAfterCommit(OrderItemCancelledEvent event) {
        outboxPublisher.publish(event);
    }

    public void publishAfterCommit(OrderItemAutoConfirmedEvent event) {
        outboxPublisher.publish(event);
    }

    public void publishAfterCommit(OrderPaidEvent event) {
        outboxPublisher.publish(event);
    }

    public void publishAfterCommit(OrderExpiredEvent event) {
        outboxPublisher.publish(event);
    }

    public void publishAfterCommit(OrderFailedEvent event) {
        outboxPublisher.publish(event);
    }

    public void publishAfterCommit(OrderItemCancelledForProductEvent event) {
        outboxPublisher.publish(event);
    }
}
