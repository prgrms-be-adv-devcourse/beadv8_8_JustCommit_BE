package com.justcommit.backend.market.order.infrastructure.scheduler;

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

    @Scheduled(fixedDelay = 30_000)
    public void expirePendingOrders() {
        orderService.expirePendingOrders();
    }

    // 성능 측정(#351): perf 프로필에서 주기만 짧게 (배송 완료 3일 뒤 확정 규칙은 그대로)
    @Scheduled(fixedDelayString = "${perf.auto-confirm-delay-ms:3600000}", initialDelay = 60_000)
    public void autoConfirmDeliveredItems() {
        orderService.autoConfirmDeliveredItems();
    }
}
