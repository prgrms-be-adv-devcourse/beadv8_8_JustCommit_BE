package com.justcommit.backend.market.order.infrastructure.kafka;

import com.justcommit.backend.market.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.market.OrderItemCancelledEvent;
import com.justcommit.backend.market.SellerOrderCancelledEvent;
import com.justcommit.backend.market.SellerOrderPaidEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
public class SellerOrderEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String paidTopic;
    private final String cancelledTopic;
    private final String itemCancelledTopic;
    private final String autoConfirmedTopic;

    public SellerOrderEventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                     @Value("${custom.kafka.topics.seller-order-paid}") String paidTopic,
                                     @Value("${custom.kafka.topics.seller-order-cancelled}") String cancelledTopic,
                                     @Value("${custom.kafka.topics.order-item-cancelled}") String itemCancelledTopic,
                                     @Value("${custom.kafka.topics.order-item-auto-confirmed}") String autoConfirmedTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.paidTopic = paidTopic;
        this.cancelledTopic = cancelledTopic;
        this.itemCancelledTopic = itemCancelledTopic;
        this.autoConfirmedTopic = autoConfirmedTopic;
    }

    public void publishAfterCommit(SellerOrderPaidEvent event) {
        register(paidTopic, event.sellerOrderId(), event);
    }

    public void publishAfterCommit(SellerOrderCancelledEvent event) {
        register(cancelledTopic, event.sellerOrderId(), event);
    }

    public void publishAfterCommit(OrderItemCancelledEvent event) {
        register(itemCancelledTopic, event.sellerOrderId(), event);
    }

    public void publishAfterCommit(OrderItemAutoConfirmedEvent event) {
        register(autoConfirmedTopic, event.sellerOrderId(), event);
    }

    private void register(String topic, Long sellerOrderId, Object event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("판매자 주문 이벤트는 주문 트랜잭션 안에서 등록해야 합니다.");
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    kafkaTemplate.send(topic, sellerOrderId.toString(), event)
                            .whenComplete((result, error) -> {
                                if (error != null) {
                                    log.error("판매자 주문 이벤트 발행 실패: topic={}, sellerOrderId={}", topic, sellerOrderId, error);
                                }
                            });
                } catch (RuntimeException error) {
                    log.error("판매자 주문 이벤트 발행 실패: topic={}, sellerOrderId={}", topic, sellerOrderId, error);
                }
                // TODO: 커밋 후 Kafka 장애 시 유실된 알림 이벤트를 재발행할 Outbox/재처리 경로가 필요하다.
            }
        });
    }
}
