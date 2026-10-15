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

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void autoConfirmDeliveredItems() {
        orderService.autoConfirmDeliveredItems();
    }
}
