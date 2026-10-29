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

    // 커밋 직후 예약·취소가 아직 진행 중인 배송과 겹치지 않게 1분 지난 배송만 다시 시도한다.
    private static final long RETRY_AFTER_MINUTES = 1;

    private final ShipmentService shipmentService;

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void rebookReadyShipments() {
        shipmentService.rebookReadyShipments(LocalDateTime.now().minusMinutes(RETRY_AFTER_MINUTES));
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public void retryCourierCancels() {
        shipmentService.retryCourierCancels(LocalDateTime.now().minusMinutes(RETRY_AFTER_MINUTES));
    }
}
