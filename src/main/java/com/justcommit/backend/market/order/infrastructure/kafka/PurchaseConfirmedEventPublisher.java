package com.justcommit.backend.market.order.infrastructure.kafka;

import com.justcommit.backend.common.event.PurchaseConfirmedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
public class PurchaseConfirmedEventPublisher {

    private final KafkaTemplate<String, PurchaseConfirmedEvent> kafkaTemplate;
    private final String topic;

    public PurchaseConfirmedEventPublisher(KafkaTemplate<String, PurchaseConfirmedEvent> kafkaTemplate,
                                           @Value("${custom.kafka.topics.purchase-confirmed}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publishAfterCommit(PurchaseConfirmedEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("구매확정 이벤트는 주문 트랜잭션 안에서 등록해야 합니다.");
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    kafkaTemplate.send(topic, event.sellerOrderId().toString(), event)
                            .whenComplete((result, error) -> {
                                if (error != null) {
                                    log.error("구매확정 이벤트 발행 실패: sellerOrderId={}", event.sellerOrderId(), error);
                                }
                            });
                } catch (RuntimeException error) {
                    log.error("구매확정 이벤트 발행 실패: sellerOrderId={}", event.sellerOrderId(), error);
                }
                // TODO: DB 커밋 후 Kafka 장애 시 유실된 정산 요청을 재발행할 Outbox/재처리 경로가 필요하다.
            }
        });
    }
}
