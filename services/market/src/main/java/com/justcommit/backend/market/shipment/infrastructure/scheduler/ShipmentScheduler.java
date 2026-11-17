package com.justcommit.backend.market.shipment.infrastructure.scheduler;

import com.justcommit.backend.market.shipment.application.ShipmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@EnableScheduling
@RequiredArgsConstructor
public class ShipmentScheduler {

    // 커밋 직후 택배사 취소가 아직 진행 중인 배송과 겹치지 않게 1분 지난 배송만 다시 시도한다.
    private static final long RETRY_AFTER_MINUTES = 1;

    private final ShipmentService shipmentService;

    // 택배 예약 큐: 10초마다 시도할 때가 된 READY 배송을 예약한다 (docs/reference/schedulers.md 「택배 예약 큐」).
    // 파드 2개가 같이 돌아도 SKIP LOCKED + 임대로 같은 배송을 두 번 예약하지 않는다.
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void bookReadyShipments() {
        shipmentService.bookReadyShipments();
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public void retryCourierCancels() {
        shipmentService.retryCourierCancels(LocalDateTime.now().minusMinutes(RETRY_AFTER_MINUTES));
    }
}
