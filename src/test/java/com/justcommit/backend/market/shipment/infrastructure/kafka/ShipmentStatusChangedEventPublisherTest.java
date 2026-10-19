package com.justcommit.backend.market.shipment.infrastructure.kafka;

import com.justcommit.backend.market.ShipmentStatusChangedEvent;
import com.justcommit.backend.market.shipment.domain.Shipment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentStatusChangedEventPublisherTest {

    private static final String TOPIC = "market.shipment-status-changed.v1";
    private static final LocalDateTime BOOKED_AT = LocalDateTime.of(2026, 10, 16, 10, 0);

    @Mock KafkaTemplate<String, ShipmentStatusChangedEvent> kafkaTemplate;

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void sendsRightAwayOutsideTransaction() {
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        publisher().publishAfterCommit(bookedShipment(), BOOKED_AT);

        verify(kafkaTemplate).send(TOPIC, "7", new ShipmentStatusChangedEvent(1L, 9L, 7L, 10L, 20L, "BOOKED", BOOKED_AT, "몬스테라"));
    }

    @Test
    void sendsOnlyAfterCommitInsideTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        publisher().publishAfterCommit(bookedShipment(), BOOKED_AT);
        verifyNoInteractions(kafkaTemplate);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(kafkaTemplate).send(anyString(), anyString(), any());
    }

    private ShipmentStatusChangedEventPublisher publisher() {
        return new ShipmentStatusChangedEventPublisher(kafkaTemplate, TOPIC);
    }

    private Shipment bookedShipment() {
        Shipment shipment = new Shipment(9L, 7L, 20L, 10L, "구매자", "01000000000", "부산", "몬스테라", new BigDecimal("3000"));
        ReflectionTestUtils.setField(shipment, "id", 1L);
        shipment.book("404000000001", BOOKED_AT);
        return shipment;
    }
}
