package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.kafka.ShipmentStatusChangedEventPublisher;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.OrderShippingInfo;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentWebhookServiceTest {

    private static final String COURIER_ORDER_NO = "shipment-20261015-000001";
    private static final String TRACKING_NO = "404000000001";
    private static final OrderShippingInfo ORDER_INFO = new OrderShippingInfo(9L, 10L, 20L, new BigDecimal("3000"), "구매자", "01000000000", "부산", null);

    @Mock ShipmentRepository shipmentRepository;
    @Mock ShipmentStatusChangedEventPublisher statusChangedEventPublisher;
    @Mock OrderQuery orderQuery;
    @InjectMocks ShipmentWebhookService shipmentWebhookService;

    @Test
    @DisplayName("반영된 웹훅마다 배송 상태 알림 이벤트를 보내고, 반영 안 된 웹훅은 보내지 않는다")
    void publishesStatusChangedOnlyWhenApplied() {
        Shipment shipment = bookedShipment();

        shipmentWebhookService.receive(webhook("PICKED_UP", "2026-10-15T10:03:00+09:00"));
        shipmentWebhookService.receive(webhook("PICKED_UP", "2026-10-15T10:03:00+09:00")); // 중복

        verify(statusChangedEventPublisher).publishAfterCommit(shipment, ORDER_INFO, LocalDateTime.of(2026, 10, 15, 10, 3));
    }

    @Test
    @DisplayName("기사가 2km 안에 들어오면(CANCEL_LOCKED 웹훅) 배송을 취소 불가로 바꾸고 상태 이벤트를 보낸다")
    void cancelLockedWebhookLocksShipment() {
        Shipment shipment = bookedShipment();

        shipmentWebhookService.receive(webhook("CANCEL_LOCKED", "2026-10-15T10:02:00+09:00"));

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCEL_LOCKED);
        verify(statusChangedEventPublisher).publishAfterCommit(shipment, ORDER_INFO, LocalDateTime.of(2026, 10, 15, 10, 2));
    }

    @Test
    @DisplayName("취소 대기 중에 온 CANCEL_LOCKED 웹훅은 무시한다 — 취소 결과는 택배 취소 큐가 정한다")
    void cancelLockedIgnoredWhileCancelPending() {
        Shipment shipment = bookedShipment();
        shipment.requestCancel(LocalDateTime.of(2026, 10, 15, 10, 1));

        shipmentWebhookService.receive(webhook("CANCEL_LOCKED", "2026-10-15T10:02:00+09:00"));

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCEL_PENDING);
        verify(statusChangedEventPublisher, never()).publishAfterCommit(any(), any(), any());
    }

    private Shipment bookedShipment() {
        Shipment shipment = new Shipment(7L, "몬스테라");
        shipment.book(TRACKING_NO, LocalDateTime.of(2026, 10, 15, 10, 0));
        when(shipmentRepository.findByCourierOrderNo(COURIER_ORDER_NO)).thenReturn(Optional.of(shipment));
        lenient().when(orderQuery.getShippingInfo(7L)).thenReturn(ORDER_INFO); // 판매자 주문에서 읽은 판매자·택배비
        return shipment;
    }

    private CourierWebhookRequest webhook(String status, String occurredAt) {
        return new CourierWebhookRequest("evt-1", COURIER_ORDER_NO, TRACKING_NO, status, OffsetDateTime.parse(occurredAt));
    }
}
