package com.justcommit.backend.market.shipment.infrastructure.kafka;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.OrderShippingInfo;
import com.justcommit.backend.market.ShipmentStatusChangedEvent;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
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
        publishAfterCommit(shipment, shipment.getStatus(), orderInfo, occurredAt);
    }

    // 현재 상태가 아니라 지나간 단계를 알릴 때 (예: 웹훅이 먼저 와 CANCEL_LOCKED가 됐지만 판매자에게는 「택배 예약됨」을 알려야 할 때)
    // 반품 배송은 발행하지 않는다 (#383). 받는 쪽이 가는 길 배송 기준으로 알림을 보내 반품 택배에 엉뚱한 알림이 가므로,
    // 반품 알림은 주문 쪽 반품 이벤트(ReturnApproved 등)로 보낸다
    public void publishAfterCommit(Shipment shipment, ShipmentStatus status, OrderShippingInfo orderInfo, LocalDateTime occurredAt) {
        if (shipment.isReturn()) {
            return;
        }
        ShipmentStatusChangedEvent event = new ShipmentStatusChangedEvent(shipment.getId(), orderInfo.orderId(),
                shipment.getSellerOrderId(), orderInfo.buyerId(), orderInfo.sellerId(), status.name(),
                occurredAt, shipment.getItemSummary());

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            outboxPublisher.publish(event);
            return;
        }
        transactionTemplate.executeWithoutResult(tx -> outboxPublisher.publish(event));
    }
}
