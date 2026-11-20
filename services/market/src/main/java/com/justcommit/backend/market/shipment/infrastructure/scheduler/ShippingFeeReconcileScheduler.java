package com.justcommit.backend.market.shipment.infrastructure.scheduler;

import com.justcommit.backend.market.shipment.application.ShippingFeeReconcileService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@RequiredArgsConstructor
public class ShippingFeeReconcileScheduler {

    private final ShippingFeeReconcileService shippingFeeReconcileService;

    // 요금표 버전 대사: 매일 09:30 서울 시각 (docs/reference/schedulers.md 「요금표 버전 대사」).
    // 행을 나눠 처리하는 작업이 아니라 ShedLock으로 파드 2개 중 한 곳만 돈다.
    // 다른 파드 시계가 조금 늦어도 다시 돌지 않게 최소 1분은 잠금을 쥐고, 택배사 호출(최대 8초)이 끝나지 않아도 5분 뒤엔 놓는다
    @Scheduled(cron = "0 30 9 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "shippingFeeReconcile", lockAtLeastFor = "PT1M", lockAtMostFor = "PT5M")
    public void reconcileShippingFees() {
        shippingFeeReconcileService.reconcile();
    }
}
