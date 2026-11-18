package com.justcommit.backend.market.shipment.infrastructure.metrics;

import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShipmentBookingDelayMetricsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 15, 0);

    private final ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ShipmentBookingDelayMetrics metrics = new ShipmentBookingDelayMetrics(shipmentRepository, registry);

    @Test
    @DisplayName("지금 - 10분보다 먼저 만들어진 READY 배송 수를 지표로 내보낸다")
    void exportsReadyOlderThanTenMinutes() {
        when(shipmentRepository.countByStatusAndCreatedAtBefore(ShipmentStatus.READY, NOW.minusMinutes(10))).thenReturn(2L);

        metrics.refresh(NOW);

        assertThat(gauge()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("세다가 실패하면 이전 값을 유지하고 예외를 밖으로 내지 않는다")
    void keepsPreviousValueOnFailure() {
        when(shipmentRepository.countByStatusAndCreatedAtBefore(any(), any()))
                .thenReturn(1L)
                .thenThrow(new IllegalStateException("DB 연결 끊김"));

        metrics.refresh(NOW);
        metrics.refresh(NOW.plusSeconds(10));

        assertThat(gauge()).isEqualTo(1.0);
    }

    private double gauge() {
        return registry.get(ShipmentBookingDelayMetrics.NAME).gauge().value();
    }
}
