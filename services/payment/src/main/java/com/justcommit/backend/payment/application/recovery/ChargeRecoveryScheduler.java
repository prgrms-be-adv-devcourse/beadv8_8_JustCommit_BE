package com.justcommit.backend.payment.application.recovery;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 결과 불명 충전 복구 스케줄러. 주기만 정하고 처리는 ChargeRecoveryService에 맡긴다
// 여러 인스턴스가 함께 돌아도 건마다 SKIP LOCKED + 임대로 나눠 가져간다
@Component
@RequiredArgsConstructor
public class ChargeRecoveryScheduler {

    private final ChargeRecoveryService chargeRecoveryService;

    @Scheduled(fixedDelayString = "${custom.payment.charge-recovery.interval:5m}")
    public void recoverDue() {
        chargeRecoveryService.recoverDue();
    }
}
