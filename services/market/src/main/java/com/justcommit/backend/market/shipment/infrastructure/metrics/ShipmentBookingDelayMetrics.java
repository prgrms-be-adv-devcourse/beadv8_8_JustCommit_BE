package com.justcommit.backend.market.shipment.infrastructure.metrics;

import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

// 배송 예약 지연 지표: 10분 넘게 READY인 배송(반품 포함) 수. 0보다 크면 Grafana가 알린다
// (docs/infra/observability.md 「배송 예약 지연」, #301)
// 수집할 때마다 DB를 읽지 않도록 택배 예약 큐가 10초마다 갱신한 값을 내보낸다.
// market 파드 2개가 같은 값을 내므로 알림은 max()로 건다
@Slf4j
@Component
public class ShipmentBookingDelayMetrics {

    static final String NAME = "market.shipments.booking.delayed"; // Prometheus: market_shipments_booking_delayed
    static final Duration THRESHOLD = Duration.ofMinutes(10);

    private final ShipmentRepository shipmentRepository;
    private final AtomicLong count = new AtomicLong();

    public ShipmentBookingDelayMetrics(ShipmentRepository shipmentRepository, MeterRegistry meterRegistry) {
        this.shipmentRepository = shipmentRepository;
        Gauge.builder(NAME, count, AtomicLong::get)
                .description("10분 넘게 택배 예약을 못 한(READY) 배송 수")
                .register(meterRegistry);
    }

    // 실패해도 이전 값을 유지하고 다음 주기에 다시 센다. 지표 때문에 예약 큐가 멈추지 않게 예외를 밖으로 내지 않는다
    public void refresh(LocalDateTime now) {
        try {
            count.set(shipmentRepository.countByStatusAndCreatedAtBefore(ShipmentStatus.READY, now.minus(THRESHOLD)));
        } catch (RuntimeException e) {
            log.warn("배송 예약 지연 수 갱신 실패, 이전 값 유지", e);
        }
    }
}
