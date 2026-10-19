package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.kafka.ShipmentStatusChangedEventPublisher;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import com.justcommit.backend.member.MemberQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentServiceTest {

    @Mock ShipmentRepository shipmentRepository;
    @Mock CourierClient courierClient;
    @Mock MemberQuery memberQuery;
    @Mock ShipmentStatusChangedEventPublisher statusChangedEventPublisher;
    @InjectMocks ShipmentService shipmentService;

    @Test
    void findsShipmentsBySellerOrderIdsInOneQuery() {
        Shipment shipment = newShipment(7L);
        ReflectionTestUtils.setField(shipment, "id", 30L);
        ReflectionTestUtils.setField(shipment, "status", ShipmentStatus.DELIVERED);
        LocalDateTime deliveredAt = LocalDateTime.of(2026, 10, 12, 13, 0);
        ReflectionTestUtils.setField(shipment, "deliveredAt", deliveredAt);
        when(shipmentRepository.findBySellerOrderIdIn(List.of(7L, 8L))).thenReturn(List.of(shipment));

        var result = shipmentService.findBySellerOrderIds(List.of(7L, 8L));

        assertThat(result).hasSize(1);
        assertThat(result.get(7L).shipmentId()).isEqualTo(30L);
        assertThat(result.get(7L).status()).isEqualTo("DELIVERED");
        assertThat(result.get(7L).deliveredAt()).isEqualTo(deliveredAt);
    }

    @Test
    void skipsQueryWhenSellerOrderIdsAreEmpty() {
        assertThat(shipmentService.findBySellerOrderIds(List.of())).isEmpty();
        verifyNoInteractions(shipmentRepository);
    }

    @Test
    void findsShipmentBySellerOrderId() {
        Shipment shipment = newShipment(7L);
        ReflectionTestUtils.setField(shipment, "id", 30L);
        ReflectionTestUtils.setField(shipment, "status", ShipmentStatus.DELIVERED);
        LocalDateTime deliveredAt = LocalDateTime.of(2026, 10, 12, 13, 0);
        ReflectionTestUtils.setField(shipment, "deliveredAt", deliveredAt);
        ReflectionTestUtils.setField(shipment, "trackingNo", "TRACK-123");
        when(shipmentRepository.findBySellerOrderId(7L)).thenReturn(Optional.of(shipment));

        var result = shipmentService.findBySellerOrderId(7L);

        assertThat(result.shipmentId()).isEqualTo(30L);
        assertThat(result.status()).isEqualTo("DELIVERED");
        assertThat(result.deliveredAt()).isEqualTo(deliveredAt);
        assertThat(result.trackingNo()).isEqualTo("TRACK-123");
    }

    @Test
    void returnsNullWhenShipmentDoesNotExist() {
        when(shipmentRepository.findBySellerOrderId(7L)).thenReturn(Optional.empty());

        assertThat(shipmentService.findBySellerOrderId(7L)).isNull();
    }

    private Shipment newShipment(Long sellerOrderId) {
        return new Shipment(sellerOrderId, 20L, 10L, "구매자", "01000000000", "부산", "몬스테라", new BigDecimal("3000"));
    }
}
