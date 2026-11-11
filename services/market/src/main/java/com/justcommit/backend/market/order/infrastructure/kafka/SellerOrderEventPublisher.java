package com.justcommit.backend.market.order.infrastructure.kafka;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.market.OrderItemCancelledEvent;
import com.justcommit.backend.market.SellerOrderCancelledEvent;
import com.justcommit.backend.market.SellerOrderPaidEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 판매자 주문 알림 이벤트를 주문과 같은 트랜잭션에 Outbox 행으로 기록하고, 커밋된 뒤 market.events.v1로 발행한다 (#277)
@Component
@RequiredArgsConstructor
public class SellerOrderEventPublisher {

    private final OutboxPublisher outboxPublisher;

    public void publishAfterCommit(SellerOrderPaidEvent event) {
        outboxPublisher.publish(event);
    }

    public void publishAfterCommit(SellerOrderCancelledEvent event) {
        outboxPublisher.publish(event);
    }

    public void publishAfterCommit(OrderItemAutoConfirmedEvent event) {
        outboxPublisher.publish(event);
    }

    public void publishAfterCommit(OrderItemCancelledEvent event) {
        outboxPublisher.publish(event);
    }
}
