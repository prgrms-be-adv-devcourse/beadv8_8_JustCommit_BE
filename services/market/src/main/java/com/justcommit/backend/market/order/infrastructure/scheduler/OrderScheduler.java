package com.justcommit.backend.market.order.infrastructure.scheduler;

import com.justcommit.backend.market.order.application.OrderRecoveryService;
import com.justcommit.backend.market.order.application.OrderService;
import com.justcommit.backend.market.order.infrastructure.metrics.ReviewRequiredOrderMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class OrderScheduler {

    private final OrderService orderService;
    private final OrderRecoveryService orderRecoveryService;
    private final ReviewRequiredOrderMetrics reviewRequiredOrderMetrics;

    @Scheduled(fixedDelay = 30_000)
    public void expirePendingOrders() {
        orderService.expirePendingOrders();
    }

    // Saga 복구: 결과 불명 결제를 같은 orderId로 다시 불러 확정하고, 택배 취소 결과를 읽어 환불, 되돌림,
    // 결과 불명 환불을 같은 키로 다시 요청한다 (주기 → docs/reference/schedulers.md).
    // 한 단계가 실패해도 다음 단계는 돈다. 끝나면 확인 필요 주문 수 지표를 갱신한다. 복구가 실패해도 지표는 센다
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void recoverProcessingPayments() {
        try {
            runStep(orderRecoveryService::recoverProcessingPayments, "결제 복구");
            runStep(orderRecoveryService::recoverCancelResults, "택배 취소 결과 처리");
            runStep(orderRecoveryService::recoverProcessingRefunds, "환불 복구");
        } finally {
            reviewRequiredOrderMetrics.refresh();
        }
    }

    private static void runStep(Runnable step, String name) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.error("Saga 복구 단계 실패, 다음 주기에 다시 시도: {}", name, e);
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void autoConfirmDeliveredItems() {
        orderService.autoConfirmDeliveredItems();
    }
}
