package com.justcommit.backend.settlement.application.payout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 정산 지급 큐 (docs/reference/schedulers.md 「정산 지급 큐」)
// 기본 10초마다 지급 대상 정산을 한 건씩(SKIP LOCKED + 임대) payment에 지급 요청하고, 끝나면 지급 지연을 확인한다.
// 파드가 여러 개여도 같은 건을 같은 회차에 겹쳐 집지 않는다. 끄려면 custom.settlement.payout.enabled=false
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "custom.settlement.payout.enabled", havingValue = "true", matchIfMissing = true)
public class SettlementPayoutScheduler {

    private final SettlementPayoutService payoutService;
    private final SettlementPayoutDelayMonitor delayMonitor;

    @Scheduled(fixedDelayString = "${custom.settlement.payout.interval:10s}",
            initialDelayString = "${custom.settlement.payout.interval:10s}")
    public void payoutDue() {
        try {
            int processed = payoutService.payoutDue();
            if (processed > 0) {
                log.info("정산 지급 처리 {}건", processed);
            }
        } catch (RuntimeException e) {
            // DB 장애 등으로 확보 자체가 실패. 다음 회차에 다시 시도
            log.warn("정산 지급 스케줄러 실행 실패", e);
        }
        try {
            delayMonitor.check();
        } catch (RuntimeException e) {
            log.warn("정산 지급 지연 확인 실패", e);
        }
    }
}
