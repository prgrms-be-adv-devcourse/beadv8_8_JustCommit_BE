package com.justcommit.backend.market.order.infrastructure.kafka;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.OrderFailedEvent;
import com.justcommit.backend.market.OrderExpiredEvent;
import com.justcommit.backend.market.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.market.OrderItemCancelReason;
import com.justcommit.backend.market.OrderItemCancelledEvent;
import com.justcommit.backend.market.OrderItemCancelledForProductEvent;
import com.justcommit.backend.market.OrderPaidEvent;
import com.justcommit.backend.market.SellerOrderCancelledEvent;
import com.justcommit.backend.market.SellerOrderPaidEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SellerOrderEventPublisherTest {

    @Mock OutboxPublisher outboxPublisher;

    @Test
    void delegatesAllOrderEventsToOutboxPublisher() {
        var publisher = new SellerOrderEventPublisher(outboxPublisher);
        var paid = new SellerOrderPaidEvent(7L, 20L, 10L, LocalDateTime.now());
        var cancelled = new SellerOrderCancelledEvent(9L, 8L, 21L, 10L, LocalDateTime.now());
        var itemCancelled = new OrderItemCancelledEvent(11L, 9L, 8L, 21L, "몬스테라", LocalDateTime.now());
        var auto = new OrderItemAutoConfirmedEvent(12L, 9L, 8L, 10L, LocalDateTime.now());
        var orderPaid = new OrderPaidEvent(9L);
        var orderExpired = new OrderExpiredEvent(9L);
        var orderFailed = new OrderFailedEvent(9L);
        var productCancelled = new OrderItemCancelledForProductEvent(9L, 11L, 100L, OrderItemCancelReason.BUYER);

        publisher.publishAfterCommit(paid);
        publisher.publishAfterCommit(cancelled);
        publisher.publishAfterCommit(itemCancelled);
        publisher.publishAfterCommit(auto);
        publisher.publishAfterCommit(orderPaid);
        publisher.publishAfterCommit(orderExpired);
        publisher.publishAfterCommit(orderFailed);
        publisher.publishAfterCommit(productCancelled);

        verify(outboxPublisher).publish(paid);
        verify(outboxPublisher).publish(cancelled);
        verify(outboxPublisher).publish(itemCancelled);
        verify(outboxPublisher).publish(auto);
        verify(outboxPublisher).publish(orderPaid);
        verify(outboxPublisher).publish(orderExpired);
        verify(outboxPublisher).publish(orderFailed);
        verify(outboxPublisher).publish(productCancelled);
    }
}
