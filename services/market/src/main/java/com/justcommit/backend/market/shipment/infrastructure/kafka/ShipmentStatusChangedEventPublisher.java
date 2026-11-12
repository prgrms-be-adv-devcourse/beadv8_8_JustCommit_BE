package com.justcommit.backend.market.shipment.infrastructure.kafka;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.OrderShippingInfo;
import com.justcommit.backend.market.ShipmentStatusChangedEvent;
import com.justcommit.backend.market.shipment.domain.Shipment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

// 배송 상태 알림 이벤트를 Outbox 행으로 기록하고, 커밋된 뒤 market.events.v1로 발행한다 (#277)
@Component
public class ShipmentStatusChangedEventPublisher {

    private final OutboxPublisher outboxPublisher;
    private final TransactionTemplate transactionTemplate;

    public ShipmentStatusChangedEventPublisher(OutboxPublisher outboxPublisher,
                                               PlatformTransactionManager transactionManager) {
        this.outboxPublisher = outboxPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // 배송의 현재 상태와 판매자 주문에서 읽은 주문 정보로 이벤트를 만들어 기록한다.
    // 웹훅은 트랜잭션 안이라 그 트랜잭션에 함께 기록한다.
    // 택배 예약은 저장이 이미 끝난 뒤라(트랜잭션 밖) Outbox 기록만 짧은 트랜잭션으로 감싼다.
    public void publishAfterCommit(Shipment shipment, OrderShippingInfo orderInfo, LocalDateTime occurredAt) {
        ShipmentStatusChangedEvent event = new ShipmentStatusChangedEvent(shipment.getId(), orderInfo.orderId(),
                shipment.getSellerOrderId(), orderInfo.buyerId(), orderInfo.sellerId(), shipment.getStatus().name(),
                occurredAt, shipment.getItemSummary());

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            outboxPublisher.publish(event);
            return;
        }
        transactionTemplate.executeWithoutResult(status -> outboxPublisher.publish(event));
    }
}
