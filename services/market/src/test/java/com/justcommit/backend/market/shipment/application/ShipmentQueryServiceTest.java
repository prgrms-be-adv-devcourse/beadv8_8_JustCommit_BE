package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentDirection;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentQueryServiceTest {

    @Mock ShipmentRepository shipmentRepository;
    @InjectMocks ShipmentQueryService shipmentQueryService;

    @Test
    void findsShipmentsBySellerOrderIdsInOneQuery() {
        Shipment shipment = newShipment(7L);
        ReflectionTestUtils.setField(shipment, "id", 30L);
        ReflectionTestUtils.setField(shipment, "status", ShipmentStatus.DELIVERED);
        LocalDateTime deliveredAt = LocalDateTime.of(2026, 10, 12, 13, 0);
        ReflectionTestUtils.setField(shipment, "deliveredAt", deliveredAt);
        when(shipmentRepository.findBySellerOrderIdInAndDirection(List.of(7L, 8L), ShipmentDirection.OUTBOUND)).thenReturn(List.of(shipment));

        var result = shipmentQueryService.findBySellerOrderIds(List.of(7L, 8L));

        assertThat(result).hasSize(1);
        assertThat(result.get(7L).shipmentId()).isEqualTo(30L);
        assertThat(result.get(7L).status()).isEqualTo("DELIVERED");
        assertThat(result.get(7L).deliveredAt()).isEqualTo(deliveredAt);
    }

    @Test
    void skipsQueryWhenSellerOrderIdsAreEmpty() {
        assertThat(shipmentQueryService.findBySellerOrderIds(List.of())).isEmpty();
        verifyNoInteractions(shipmentRepository);
    }

    @Test
    void findsReturnShipmentsByShipmentIdsAndOmitsMissingIds() {
        Shipment returnShipment = newShipment(7L);
        ReflectionTestUtils.setField(returnShipment, "id", 31L);
        ReflectionTestUtils.setField(returnShipment, "direction", ShipmentDirection.RETURN);
        ReflectionTestUtils.setField(returnShipment, "status", ShipmentStatus.DELIVERED);
        LocalDateTime deliveredAt = LocalDateTime.of(2026, 10, 15, 14, 0);
        ReflectionTestUtils.setField(returnShipment, "deliveredAt", deliveredAt);
        when(shipmentRepository.findAllById(List.of(31L, 99L))).thenReturn(List.of(returnShipment));

        var result = shipmentQueryService.findByShipmentIds(List.of(31L, 99L));

        assertThat(result).containsOnlyKeys(31L);
        assertThat(result.get(31L).status()).isEqualTo("DELIVERED");
        assertThat(result.get(31L).deliveredAt()).isEqualTo(deliveredAt);
    }

    @Test
    void skipsQueryWhenShipmentIdsAreEmpty() {
        assertThat(shipmentQueryService.findByShipmentIds(List.of())).isEmpty();
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
        ReflectionTestUtils.setField(shipment, "bookedAt", LocalDateTime.of(2026, 10, 12, 10, 0));
        ReflectionTestUtils.setField(shipment, "pickedUpAt", LocalDateTime.of(2026, 10, 12, 10, 3));
        ReflectionTestUtils.setField(shipment, "inTransitAt", LocalDateTime.of(2026, 10, 12, 10, 5));
        when(shipmentRepository.findBySellerOrderIdAndDirection(7L, ShipmentDirection.OUTBOUND)).thenReturn(Optional.of(shipment));

        var result = shipmentQueryService.findBySellerOrderId(7L);

        assertThat(result.shipmentId()).isEqualTo(30L);
        assertThat(result.status()).isEqualTo("DELIVERED");
        assertThat(result.deliveredAt()).isEqualTo(deliveredAt);
        assertThat(result.trackingNo()).isEqualTo("TRACK-123");
        assertThat(result.bookedAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 10, 0));
        assertThat(result.pickedUpAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 10, 3));
        assertThat(result.inTransitAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 10, 5));
    }

    @Test
    void returnsNullWhenShipmentDoesNotExist() {
        when(shipmentRepository.findBySellerOrderIdAndDirection(7L, ShipmentDirection.OUTBOUND)).thenReturn(Optional.empty());

        assertThat(shipmentQueryService.findBySellerOrderId(7L)).isNull();
    }

    private Shipment newShipment(Long sellerOrderId) {
        return new Shipment(sellerOrderId, "몬스테라");
    }
}
