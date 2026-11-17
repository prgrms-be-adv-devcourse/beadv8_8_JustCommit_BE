package com.justcommit.backend.market.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.client.payment.PaymentOutcome;
import com.justcommit.backend.market.client.payment.PaymentUseCase;
import com.justcommit.backend.market.order.application.OrderPaymentTransactions.AttemptSource;
import com.justcommit.backend.market.order.application.OrderPaymentTransactions.RecoveryClaim;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderRecoveryServiceTest {

    @Mock OrdersRepository ordersRepository;
    @Mock OrderPaymentTransactions orderPaymentTransactions;
    @Mock PaymentUseCase paymentUseCase;
    @InjectMocks OrderRecoveryService service;

    @Test
    @DisplayName("유예(10초)가 지난 결제 처리 중 주문을 최대 50건 후보로 읽는다")
    void readsCandidatesAfterGrace() {
        LocalDateTime before = LocalDateTime.now();
        when(ordersRepository.findRecoveryCandidateIds(eq(OrderStatus.PAYMENT_PROCESSING), any(), eq(50)))
                .thenAnswer(invocation -> {
                    LocalDateTime attemptedBefore = invocation.getArgument(1);
                    assertThat(attemptedBefore).isBetween(before.minusSeconds(11), LocalDateTime.now().minusSeconds(10));
                    return List.of();
                });

        service.recoverProcessingPayments();

        verify(paymentUseCase, never()).pay(any(), any(), any());
    }

    @Test
    @DisplayName("선점하면 트랜잭션 밖에서 같은 orderId · 저장된 금액으로 결제를 다시 부르고 결과를 반영한다")
    void retriesClaimedOrderWithSameOrderId() {
        RecoveryClaim claim = retry(99L, 3, 2);
        stubCandidates(99L);
        when(orderPaymentTransactions.claimForRecovery(99L)).thenReturn(claim);
        when(paymentUseCase.pay(99L, 10L, new BigDecimal("13000"))).thenReturn(PaymentOutcome.paid());

        service.recoverProcessingPayments();

        verify(orderPaymentTransactions).applyRecoveryOutcome(claim, PaymentOutcome.paid());
    }

    @Test
    @DisplayName("다른 파드가 잡았거나 확인 필요로 바꾼 주문은 결제를 부르지 않는다")
    void doesNotCallPaymentWhenNotRetrying() {
        stubCandidates(98L, 99L);
        when(orderPaymentTransactions.claimForRecovery(98L)).thenReturn(RecoveryClaim.skipped(98L));
        when(orderPaymentTransactions.claimForRecovery(99L)).thenReturn(new RecoveryClaim(
                RecoveryClaim.Type.REVIEW_REQUIRED, 99L, 7, 5, 10L, new BigDecimal("13000")));

        service.recoverProcessingPayments();

        verify(paymentUseCase, never()).pay(any(), any(), any());
        verify(orderPaymentTransactions, never()).applyRecoveryOutcome(any(), any());
    }

    @Test
    @DisplayName("한 주문의 복구가 실패해도 다음 주문은 계속 처리한다")
    void continuesWithNextOrderAfterFailure() {
        stubCandidates(98L, 99L);
        when(orderPaymentTransactions.claimForRecovery(98L)).thenThrow(new IllegalStateException("DB 오류"));
        RecoveryClaim claim = retry(99L, 2, 1);
        when(orderPaymentTransactions.claimForRecovery(99L)).thenReturn(claim);
        when(paymentUseCase.pay(99L, 10L, new BigDecimal("13000"))).thenReturn(PaymentOutcome.unknown("TIMEOUT"));

        service.recoverProcessingPayments();

        verify(orderPaymentTransactions).applyRecoveryOutcome(claim, PaymentOutcome.unknown("TIMEOUT"));
    }

    @Test
    @DisplayName("복구 경로의 결과별 주문 상태: 연결 실패도 결과 불명으로 결제 처리 중 유지")
    void recoveryTargetStatus() {
        assertThat(OrderPaymentTransactions.targetStatus(PaymentOutcome.paid(), AttemptSource.RECOVERY))
                .isEqualTo(OrderStatus.PAID);
        assertThat(OrderPaymentTransactions.targetStatus(PaymentOutcome.insufficientBalance(), AttemptSource.RECOVERY))
                .isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(OrderPaymentTransactions.targetStatus(PaymentOutcome.rejected("HTTP_401"), AttemptSource.RECOVERY))
                .isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(OrderPaymentTransactions.targetStatus(PaymentOutcome.notDelivered(), AttemptSource.RECOVERY))
                .isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        assertThat(OrderPaymentTransactions.targetStatus(PaymentOutcome.unknown("HTTP_503"), AttemptSource.RECOVERY))
                .isEqualTo(OrderStatus.PAYMENT_PROCESSING);
        assertThat(OrderPaymentTransactions.targetStatus(PaymentOutcome.notDelivered(), AttemptSource.FIRST_CALL))
                .isEqualTo(OrderStatus.PAYMENT_FAILED);
    }

    private void stubCandidates(Long... orderIds) {
        when(ordersRepository.findRecoveryCandidateIds(eq(OrderStatus.PAYMENT_PROCESSING), any(), anyInt()))
                .thenReturn(List.of(orderIds));
    }

    private static RecoveryClaim retry(Long orderId, int attemptNo, int attemptCount) {
        return new RecoveryClaim(RecoveryClaim.Type.RETRY, orderId, attemptNo, attemptCount, 10L, new BigDecimal("13000"));
    }
}
