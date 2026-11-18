package com.justcommit.backend.market.order.infrastructure.scheduler;

import com.justcommit.backend.market.order.application.OrderRecoveryService;
import com.justcommit.backend.market.order.application.OrderService;
import com.justcommit.backend.market.order.infrastructure.metrics.ReviewRequiredOrderMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    // Saga 복구: 결과 불명 결제를 같은 orderId로 다시 불러 확정 (주기 → docs/reference/schedulers.md)
    // 끝나면 확인 필요 주문 수 지표를 갱신한다. 복구가 실패해도 지표는 센다
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void recoverProcessingPayments() {
        try {
            orderRecoveryService.recoverProcessingPayments();
        } finally {
            reviewRequiredOrderMetrics.refresh();
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void autoConfirmDeliveredItems() {
        orderService.autoConfirmDeliveredItems();
    }
}
