package com.justcommit.backend.market.order.application;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@RequiredArgsConstructor
public class OrderExpirationJob {

    private final OrderService orderService;

    @Scheduled(fixedDelay = 30_000)
    public void expirePendingOrders() {
        orderService.expirePendingOrders();
    }
}
