package com.justcommit.backend.market.shipment.infrastructure.metrics;

import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

// 오래 남은 배송 지표 (docs/infra/observability.md 「알림 규칙」).
// 택배 예약 · 취소 큐는 포기하지 않고 끝까지 재시도하므로, 오래 남은 건은 건마다가 아니라 "남은 건이 있다"는 상태로 알린다.
// Prometheus가 수집할 때마다 DB에서 센다. Grafana가 1 이상이면 Slack으로 상태 기준 한 번 알린다.
//   replant_shipment_stuck{status="READY"}          만든 지 10분 넘게 택배 예약이 안 된 배송 수
//   replant_shipment_stuck{status="CANCEL_PENDING"} 취소를 누른 지 10분 넘게 택배사 결과를 못 받은 배송 수
@Slf4j
@Component
public class StuckShipmentMetrics {
    static final String NAME = "replant.shipment.stuck";
    static final Duration STUCK_AFTER = Duration.ofMinutes(10);

    private final ShipmentRepository shipmentRepository;

    public StuckShipmentMetrics(ShipmentRepository shipmentRepository, MeterRegistry registry) {
        this.shipmentRepository = shipmentRepository;
        Gauge.builder(NAME, this, StuckShipmentMetrics::stuckReady)
                .description("10분 넘게 택배 예약이 안 된 배송 수")
                .tag("status", ShipmentStatus.READY.name())
                .register(registry);
        Gauge.builder(NAME, this, StuckShipmentMetrics::stuckCancelPending)
                .description("취소를 누른 지 10분 넘게 택배사 결과를 못 받은 배송 수")
                .tag("status", ShipmentStatus.CANCEL_PENDING.name())
                .register(registry);
    }

    double stuckReady() {
        return count(() -> shipmentRepository.countByStatusAndCreatedAtBefore(
                ShipmentStatus.READY, LocalDateTime.now().minus(STUCK_AFTER)));
    }

    double stuckCancelPending() {
        return count(() -> shipmentRepository.countByStatusAndCancelRequestedAtBefore(
                ShipmentStatus.CANCEL_PENDING, LocalDateTime.now().minus(STUCK_AFTER)));
    }

    // DB를 못 읽으면 0이 아니라 "값 없음"(NaN)으로 둬서 알림이 거짓으로 꺼지지 않게 한다
    private double count(java.util.function.LongSupplier query) {
        try {
            return query.getAsLong();
        } catch (RuntimeException e) {
            log.warn("오래 남은 배송 수를 세지 못함: {}", e.getMessage());
            return Double.NaN;
        }
    }
}
