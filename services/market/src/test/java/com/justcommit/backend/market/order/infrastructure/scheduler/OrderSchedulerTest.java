package com.justcommit.backend.market.order.infrastructure.scheduler;

import com.justcommit.backend.market.order.application.OrderRecoveryService;
import com.justcommit.backend.market.order.application.OrderService;
import com.justcommit.backend.market.order.infrastructure.metrics.ReviewRequiredOrderMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderSchedulerTest {

    @Mock OrderService orderService;
    @Mock OrderRecoveryService orderRecoveryService;
    @Mock ReviewRequiredOrderMetrics reviewRequiredOrderMetrics;
    @InjectMocks OrderScheduler orderScheduler;

    @Test
    void delegatesPendingOrderExpirationToOrderService() {
        orderScheduler.expirePendingOrders();

        verify(orderService).expirePendingOrders();
    }

    @Test
    void delegatesPaymentRecoveryToRecoveryServiceThenRefreshesReviewMetric() {
        orderScheduler.recoverProcessingPayments();

        var order = inOrder(orderRecoveryService, reviewRequiredOrderMetrics);
        order.verify(orderRecoveryService).recoverProcessingPayments();
        order.verify(reviewRequiredOrderMetrics).refresh();
    }

    @Test
    void runsCancelRecoveryStepsAfterPaymentRecovery() {
        orderScheduler.recoverProcessingPayments();

        var order = inOrder(orderRecoveryService, reviewRequiredOrderMetrics);
        order.verify(orderRecoveryService).recoverProcessingPayments();
        order.verify(orderRecoveryService).recoverCancelResults();
        order.verify(orderRecoveryService).recoverProcessingRefunds();
        order.verify(orderRecoveryService).recoverReturnArrivals();
        order.verify(orderRecoveryService).recoverReturnRefunds();
        order.verify(reviewRequiredOrderMetrics).refresh();
    }

    @Test
    void runsReturnRefundEvenWhenReturnArrivalFails() {
        doThrow(new IllegalStateException("db down")).when(orderRecoveryService).recoverReturnArrivals();

        orderScheduler.recoverProcessingPayments();

        verify(orderRecoveryService).recoverReturnRefunds();
        verify(reviewRequiredOrderMetrics).refresh();
    }

    @Test
    void runsNextStepsAndRefreshesReviewMetricEvenWhenRecoveryFails() {
        doThrow(new IllegalStateException("db down")).when(orderRecoveryService).recoverProcessingPayments();

        orderScheduler.recoverProcessingPayments();

        verify(orderRecoveryService).recoverCancelResults();
        verify(orderRecoveryService).recoverProcessingRefunds();
        verify(reviewRequiredOrderMetrics).refresh();
    }

    @Test
    void delegatesAutoConfirmationToOrderService() {
        orderScheduler.autoConfirmDeliveredItems();

        verify(orderService).autoConfirmDeliveredItems();
    }

    @Test
    void delegatesReturnAutoApprovalToOrderService() {
        orderScheduler.autoApproveExpiredReturns();

        verify(orderService).autoApproveExpiredReturns();
    }
}
