package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.ShipmentPickedUpEvent;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.kafka.ShipmentPickedUpEventPublisher;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import com.justcommit.backend.market.shipment.presentation.CourierWebhookRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentWebhookServiceTest {

    private static final String COURIER_ORDER_NO = "shipment-20261015-000001";
    private static final String TRACKING_NO = "404000000001";

    @Mock ShipmentRepository shipmentRepository;
    @Mock ShipmentPickedUpEventPublisher pickedUpEventPublisher;
    @InjectMocks ShipmentWebhookService shipmentWebhookService;

    @Test
    @DisplayName("수거 완료 웹훅을 받으면 택배비 정산 이벤트를 보낸다")
    void publishesWhenPickedUp() {
        bookedShipment();

        shipmentWebhookService.receive(webhook("PICKED_UP", "2026-10-15T10:03:00+09:00"));

        verify(pickedUpEventPublisher).publishAfterCommit(new ShipmentPickedUpEvent(
                7L, 20L, new BigDecimal("3000"), LocalDateTime.of(2026, 10, 15, 10, 3)));
    }

    @Test
    @DisplayName("수거 완료 웹훅을 놓치고 배송 중이 먼저 와도 이벤트를 보낸다")
    void publishesWhenPickedUpSkipped() {
        bookedShipment();

        shipmentWebhookService.receive(webhook("IN_TRANSIT", "2026-10-15T10:05:00+09:00"));

        verify(pickedUpEventPublisher).publishAfterCommit(new ShipmentPickedUpEvent(
                7L, 20L, new BigDecimal("3000"), LocalDateTime.of(2026, 10, 15, 10, 5)));
    }

    @Test
    @DisplayName("이미 수거된 배송은 다음 상태가 와도 이벤트를 다시 보내지 않는다")
    void doesNotPublishTwice() {
        Shipment shipment = bookedShipment();
        shipment.applyCourierStatus(ShipmentStatus.PICKED_UP, TRACKING_NO, LocalDateTime.of(2026, 10, 15, 10, 3));

        shipmentWebhookService.receive(webhook("IN_TRANSIT", "2026-10-15T10:05:00+09:00"));
        shipmentWebhookService.receive(webhook("DELIVERED", "2026-10-15T15:00:00+09:00"));

        verify(pickedUpEventPublisher, never()).publishAfterCommit(any());
    }

    @Test
    @DisplayName("같은 수거 완료 웹훅이 다시 와도 이벤트를 다시 보내지 않는다")
    void doesNotPublishOnDuplicate() {
        bookedShipment();

        shipmentWebhookService.receive(webhook("PICKED_UP", "2026-10-15T10:03:00+09:00"));
        shipmentWebhookService.receive(webhook("PICKED_UP", "2026-10-15T10:03:00+09:00"));

        verify(pickedUpEventPublisher).publishAfterCommit(any());
    }

    private Shipment bookedShipment() {
        Shipment shipment = new Shipment(7L, 20L, "구매자", "01000000000", "부산", "몬스테라", new BigDecimal("3000"));
        shipment.book(TRACKING_NO, LocalDateTime.of(2026, 10, 15, 10, 0));
        when(shipmentRepository.findByCourierOrderNo(COURIER_ORDER_NO)).thenReturn(Optional.of(shipment));
        return shipment;
    }

    private CourierWebhookRequest webhook(String status, String occurredAt) {
        return new CourierWebhookRequest("evt-1", COURIER_ORDER_NO, TRACKING_NO, status, OffsetDateTime.parse(occurredAt));
    }
}
