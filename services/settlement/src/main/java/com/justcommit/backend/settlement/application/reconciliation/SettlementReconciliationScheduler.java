package com.justcommit.backend.settlement.application.reconciliation;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 정산 대사 (docs/reference/schedulers.md 「정산 대사」)
// 매일 09:40(Asia/Seoul)에 한 번. EC2가 09:00~18:00에만 켜져 있어 새벽에는 돌지 않는다.
// 파드가 여러 개면 ShedLock으로 한 곳에서만 돈다. 끄려면 custom.settlement.reconciliation.enabled=false
// (끄면 미실행 알림이 울린다)
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "custom.settlement.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class SettlementReconciliationScheduler {

    private final SettlementReconciliationService reconciliationService;

    @Scheduled(cron = "${custom.settlement.reconciliation.cron:0 40 9 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "settlementReconcile", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void reconcile() {
        reconciliationService.reconcile();
    }
}
