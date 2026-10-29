package com.justcommit.backend.market.order.infrastructure.scheduler;

import com.justcommit.backend.market.order.application.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderSchedulerTest {

    @Mock OrderService orderService;
    @InjectMocks OrderScheduler orderScheduler;

    @Test
    void delegatesPendingOrderExpirationToOrderService() {
        orderScheduler.expirePendingOrders();

        verify(orderService).expirePendingOrders();
    }

    @Test
    void delegatesAutoConfirmationToOrderService() {
        orderScheduler.autoConfirmDeliveredItems();

        verify(orderService).autoConfirmDeliveredItems();
    }
}
