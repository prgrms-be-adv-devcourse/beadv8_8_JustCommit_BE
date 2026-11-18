package com.justcommit.backend.market.order.infrastructure.kafka;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.PurchaseConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 구매 확정과 같은 트랜잭션에 Outbox 행으로 기록하고, 커밋된 뒤 market.events.v1로 발행한다 (#277)
// 트랜잭션 밖에서 부르면 OutboxPublisher가 예외를 던진다 (예전과 같은 규칙)
@Component
@RequiredArgsConstructor
public class PurchaseConfirmedEventPublisher {

    private final OutboxPublisher outboxPublisher;

    public void publishAfterCommit(PurchaseConfirmedEvent event) {
        outboxPublisher.publish(event);
    }
}
