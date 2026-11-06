package com.justcommit.backend.market.shipment.infrastructure.kafka;

import com.justcommit.backend.market.ShipmentPickedUpEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
public class ShipmentPickedUpEventPublisher {

    private final KafkaTemplate<String, ShipmentPickedUpEvent> kafkaTemplate;
    private final String topic;

    public ShipmentPickedUpEventPublisher(KafkaTemplate<String, ShipmentPickedUpEvent> kafkaTemplate,
                                          @Value("${custom.kafka.topics.shipment-picked-up}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    // 웹훅 반영이 커밋된 뒤에만 보낸다. 롤백되면 보내지 않는다.
    public void publishAfterCommit(ShipmentPickedUpEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("수거 완료 이벤트는 배송 트랜잭션 안에서 등록해야 합니다.");
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    // 키를 판매자 주문 id로 해서 같은 주문의 구매확정 이벤트와 순서를 맞춘다
                    kafkaTemplate.send(topic, event.sellerOrderId().toString(), event)
                            .whenComplete((result, error) -> {
                                if (error != null) {
                                    log.error("수거 완료 이벤트 발행 실패: sellerOrderId={}", event.sellerOrderId(), error);
                                }
                            });
                } catch (RuntimeException error) {
                    log.error("수거 완료 이벤트 발행 실패: sellerOrderId={}", event.sellerOrderId(), error);
                }
                // TODO: Kafka 장애 시 유실된 이벤트를 다시 보낼 Outbox/재처리 경로가 필요하다. (구매확정 이벤트와 같은 과제)
            }
        });
    }
}
