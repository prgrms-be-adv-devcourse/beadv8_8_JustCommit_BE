package com.justcommit.backend.market.shipment.infrastructure.kafka;

import com.justcommit.backend.market.ShipmentPickedUpEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentPickedUpEventPublisherTest {

    private static final String TOPIC = "market.shipment-picked-up.v1";

    @Mock KafkaTemplate<String, ShipmentPickedUpEvent> kafkaTemplate;

    @BeforeEach
    void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void sendsOnlyAfterCommit() {
        ShipmentPickedUpEvent event = event();
        ShipmentPickedUpEventPublisher publisher = new ShipmentPickedUpEventPublisher(kafkaTemplate, TOPIC);
        when(kafkaTemplate.send(TOPIC, "7", event)).thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishAfterCommit(event);
        verifyNoInteractions(kafkaTemplate);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(kafkaTemplate).send(TOPIC, "7", event);
    }

    @Test
    void doesNotSendAfterRollback() {
        ShipmentPickedUpEventPublisher publisher = new ShipmentPickedUpEventPublisher(kafkaTemplate, TOPIC);

        publisher.publishAfterCommit(event());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(kafkaTemplate);
    }

    private ShipmentPickedUpEvent event() {
        return new ShipmentPickedUpEvent(7L, 20L, new BigDecimal("3000"), LocalDateTime.of(2026, 10, 15, 10, 3));
    }
}
