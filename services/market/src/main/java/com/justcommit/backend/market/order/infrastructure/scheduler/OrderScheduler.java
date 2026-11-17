package com.justcommit.backend.market.order.infrastructure.scheduler;

import com.justcommit.backend.market.order.application.OrderRecoveryService;
import com.justcommit.backend.market.order.application.OrderService;
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

    @Scheduled(fixedDelay = 30_000)
    public void expirePendingOrders() {
        orderService.expirePendingOrders();
    }

    // Saga 복구: 결과 불명 결제를 같은 orderId로 다시 불러 확정 (주기 → docs/reference/schedulers.md)
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void recoverProcessingPayments() {
        orderRecoveryService.recoverProcessingPayments();
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void autoConfirmDeliveredItems() {
        orderService.autoConfirmDeliveredItems();
    }
}
