package com.justcommit.backend.market.shipment.infrastructure.scheduler;

import com.justcommit.backend.market.shipment.application.ShipmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
@EnableScheduling
@RequiredArgsConstructor
public class ShipmentScheduler {

    // 커밋 직후 예약·취소가 아직 진행 중인 배송과 겹치지 않게 1분 지난 배송만 다시 시도한다.
    private static final long RETRY_AFTER_MINUTES = 1;

    // 취소 시각은 서울 기준으로 저장하므로 비교 시각도 서울 기준으로 만든다.
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ShipmentService shipmentService;

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void rebookReadyShipments() {
        shipmentService.rebookReadyShipments(LocalDateTime.now().minusMinutes(RETRY_AFTER_MINUTES));
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public void retryCourierCancels() {
        shipmentService.retryCourierCancels(LocalDateTime.now(SEOUL).minusMinutes(RETRY_AFTER_MINUTES));
    }
}
