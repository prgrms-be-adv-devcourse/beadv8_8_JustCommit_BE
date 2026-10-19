package com.justcommit.backend.market.shipment.infrastructure.kafka;

import com.justcommit.backend.market.ShipmentStatusChangedEvent;
import com.justcommit.backend.market.shipment.domain.Shipment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

@Slf4j
@Component
public class ShipmentStatusChangedEventPublisher {

    private final KafkaTemplate<String, ShipmentStatusChangedEvent> kafkaTemplate;
    private final String topic;

    public ShipmentStatusChangedEventPublisher(KafkaTemplate<String, ShipmentStatusChangedEvent> kafkaTemplate,
                                               @Value("${custom.kafka.topics.shipment-status-changed}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    // 배송의 현재 상태로 이벤트를 만들어 보낸다.
    // 트랜잭션 안이면 커밋 후에 보낸다(웹훅). 밖이면 이미 저장이 끝난 것이므로 바로 보낸다(커밋 후 예약).
    public void publishAfterCommit(Shipment shipment, LocalDateTime occurredAt) {
        ShipmentStatusChangedEvent event = new ShipmentStatusChangedEvent(shipment.getId(), shipment.getOrderId(), shipment.getSellerOrderId(),
                shipment.getBuyerId(), shipment.getSellerId(), shipment.getStatus().name(), occurredAt);

        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            send(event);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(event);
            }
        });
    }

    private void send(ShipmentStatusChangedEvent event) {
        try {
            // 키를 판매자 주문 id로 해서 같은 배송의 상태 이벤트가 순서대로 처리되게 한다
            kafkaTemplate.send(topic, event.sellerOrderId().toString(), event)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.error("배송 상태 이벤트 발행 실패: shipmentId={}, status={}",
                                    event.shipmentId(), event.status(), error);
                        }
                    });
        } catch (RuntimeException error) {
            // 알림용 이벤트라 발행 실패가 배송 처리를 막지 않게 한다
            log.error("배송 상태 이벤트 발행 실패: shipmentId={}, status={}", event.shipmentId(), event.status(), error);
        }
    }
}
