package com.justcommit.backend.market.order.infrastructure.kafka;

import com.justcommit.backend.market.OrderItemAutoConfirmedEvent;
import com.justcommit.backend.market.SellerOrderCancelledEvent;
import com.justcommit.backend.market.SellerOrderPaidEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SellerOrderEventPublisherTest {

    @Mock KafkaTemplate<String, Object> kafkaTemplate;

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
    void sendsEachEventOnlyAfterCommit() {
        var publisher = new SellerOrderEventPublisher(kafkaTemplate, "paid", "cancelled", "auto");
        var paid = new SellerOrderPaidEvent(7L, 20L, 10L, LocalDateTime.now());
        var cancelled = new SellerOrderCancelledEvent(8L, 21L, 10L, LocalDateTime.now());
        var auto = new OrderItemAutoConfirmedEvent(11L, 7L, 10L, LocalDateTime.now());
        when(kafkaTemplate.send("paid", "7", paid)).thenReturn(CompletableFuture.completedFuture(null));
        when(kafkaTemplate.send("cancelled", "8", cancelled)).thenReturn(CompletableFuture.completedFuture(null));
        when(kafkaTemplate.send("auto", "7", auto)).thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishAfterCommit(paid);
        publisher.publishAfterCommit(cancelled);
        publisher.publishAfterCommit(auto);
        verifyNoInteractions(kafkaTemplate);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(kafkaTemplate).send("paid", "7", paid);
        verify(kafkaTemplate).send("cancelled", "8", cancelled);
        verify(kafkaTemplate).send("auto", "7", auto);
    }

    @Test
    void sendsNothingAfterRollback() {
        var publisher = new SellerOrderEventPublisher(kafkaTemplate, "paid", "cancelled", "auto");
        publisher.publishAfterCommit(new SellerOrderPaidEvent(7L, 20L, 10L, LocalDateTime.now()));

        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(kafkaTemplate);
    }
}
