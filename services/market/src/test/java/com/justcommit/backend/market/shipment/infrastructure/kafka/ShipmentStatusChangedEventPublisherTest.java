package com.justcommit.backend.market.shipment.infrastructure.kafka;

import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.market.OrderShippingInfo;
import com.justcommit.backend.market.ShipmentStatusChangedEvent;
import com.justcommit.backend.market.shipment.domain.Shipment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ShipmentStatusChangedEventPublisherTest {

    private static final OrderShippingInfo ORDER_INFO = new OrderShippingInfo(9L, 10L, 20L, new BigDecimal("3000"), "구매자", "01000000000", "부산", null);
    private static final LocalDateTime BOOKED_AT = LocalDateTime.of(2026, 10, 16, 10, 0);
    private static final ShipmentStatusChangedEvent EXPECTED =
            new ShipmentStatusChangedEvent(1L, 9L, 7L, 10L, 20L, "BOOKED", BOOKED_AT, "몬스테라");

    private final OutboxPublisher outboxPublisher = mock(OutboxPublisher.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ShipmentStatusChangedEventPublisher publisher =
            new ShipmentStatusChangedEventPublisher(outboxPublisher, transactionManager);

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("웹훅처럼 트랜잭션 안이면 그 트랜잭션에 바로 기록한다")
    void publishesInCurrentTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        publisher.publishAfterCommit(bookedShipment(), ORDER_INFO, BOOKED_AT);

        verify(outboxPublisher).publish(EXPECTED);
        verifyNoInteractions(transactionManager);
    }

    @Test
    @DisplayName("택배 예약처럼 트랜잭션 밖이면 Outbox 기록만 짧은 트랜잭션으로 감싸 커밋한다")
    void wrapsInNewTransactionOutsideTransaction() {
        publisher.publishAfterCommit(bookedShipment(), ORDER_INFO, BOOKED_AT);

        verify(transactionManager).getTransaction(any());
        verify(outboxPublisher).publish(EXPECTED);
        verify(transactionManager).commit(any());
    }

    private Shipment bookedShipment() {
        Shipment shipment = new Shipment(7L, "몬스테라");
        ReflectionTestUtils.setField(shipment, "id", 1L);
        shipment.book("404000000001", BOOKED_AT);
        return shipment;
    }
}
