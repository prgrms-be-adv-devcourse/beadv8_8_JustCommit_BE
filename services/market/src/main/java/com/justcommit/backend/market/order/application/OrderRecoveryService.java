package com.justcommit.backend.market.order.application;

import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.client.payment.PaymentOutcome;
import com.justcommit.backend.market.client.payment.PaymentUseCase;
import com.justcommit.backend.market.order.application.OrderPaymentTransactions.RecoveryClaim;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

// Saga 복구: 결제 결과를 모르는 주문(PAYMENT_PROCESSING)을 같은 orderId로 다시 결제해 확정한다 (ADR 0023, 정책 4.4)
// market 파드 2개가 함께 돌아도 주문마다 SKIP LOCKED 선점 + 시도 번호로 한 시도만 결과를 반영한다
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderRecoveryService {

    // 한 번 실행에 집는 최대 주문 수. 결제가 계속 타임아웃이면 주문당 약 8초라 최악 약 7분
    static final int RECOVERY_BATCH_SIZE = 50;

    private final OrdersRepository ordersRepository;
    private final OrderPaymentTransactions orderPaymentTransactions;
    private final PaymentUseCase paymentUseCase;

    // 후보는 잠그지 않고 읽고, 주문마다 선점 → (트랜잭션 밖) 결제 재호출 → 결과 반영. 한 주문이 실패해도 다음 주문은 계속한다
    public void recoverProcessingPayments() {
        List<Long> candidates = ordersRepository.findRecoveryCandidateIds(OrderStatus.PAYMENT_PROCESSING,
                LocalDateTime.now().minus(OrderPaymentTransactions.RECOVERY_GRACE), RECOVERY_BATCH_SIZE);
        for (Long orderId : candidates) {
            try {
                recover(orderId);
            } catch (RuntimeException e) {
                // 선점 뒤 실패해도 주문은 결제 처리 중으로 남아 다음 주기에 다시 집힌다 (횟수는 이미 셌음)
                log.error("결제 복구 실패, 다음 주기에 다시 시도: orderId={}", orderId, e);
            }
        }
    }

    private void recover(Long orderId) {
        RecoveryClaim claim = orderPaymentTransactions.claimForRecovery(orderId);
        if (claim.type() != RecoveryClaim.Type.RETRY) {
            return;
        }
        log.info("결제 복구 재호출: orderId={}, attemptNo={}, attemptCount={}",
                orderId, claim.attemptNo(), claim.attemptCount());
        PaymentOutcome outcome = paymentUseCase.pay(orderId, claim.buyerId(), claim.amount());
        orderPaymentTransactions.applyRecoveryOutcome(claim, outcome);
    }
}
