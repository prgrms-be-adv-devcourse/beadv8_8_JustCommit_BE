package com.justcommit.backend.market.order.infrastructure.kafka;

import com.justcommit.backend.common.event.PurchaseConfirmedEvent;
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
class PurchaseConfirmedEventPublisherTest {

    @Mock KafkaTemplate<String, PurchaseConfirmedEvent> kafkaTemplate;

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
        PurchaseConfirmedEvent event = event();
        PurchaseConfirmedEventPublisher publisher = new PurchaseConfirmedEventPublisher(
                kafkaTemplate, "market.purchase-confirmed.v1");
        when(kafkaTemplate.send("market.purchase-confirmed.v1", "7", event))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishAfterCommit(event);
        verifyNoInteractions(kafkaTemplate);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(kafkaTemplate).send("market.purchase-confirmed.v1", "7", event);
    }

    @Test
    void doesNotSendAfterRollback() {
        PurchaseConfirmedEventPublisher publisher = new PurchaseConfirmedEventPublisher(
                kafkaTemplate, "market.purchase-confirmed.v1");

        publisher.publishAfterCommit(event());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(kafkaTemplate);
    }

    private PurchaseConfirmedEvent event() {
        return new PurchaseConfirmedEvent(7L, 20L, new BigDecimal("10000"),
                LocalDateTime.of(2026, 10, 14, 12, 0), "몬스테라");
    }
}
