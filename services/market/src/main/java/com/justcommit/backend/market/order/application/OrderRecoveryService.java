package com.justcommit.backend.market.order.application;

import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.ShipmentQuery;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
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
    private final OrdersItemRepository ordersItemRepository;
    private final ShipmentQuery shipmentQuery;
    private final OrderCancelTransactions orderCancelTransactions;
    private final OrderCancelRefunder orderCancelRefunder;

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

    // 택배 취소 결과를 읽어 취소됨이면 환불, 취소 불가면 주문상품을 되돌린다 (#282, ADR 0026)
    public void recoverCancelResults() {
        for (Long resultId : shipmentQuery.findUnprocessedCancelResultIds(RECOVERY_BATCH_SIZE)) {
            try {
                orderCancelRefunder.refund(orderCancelTransactions.claimCancelResult(resultId));
            } catch (RuntimeException e) {
                // 처리 표시 전에 실패했으면 다음 주기에 다시 집힌다. 환불 선점 뒤라면 환불 재요청이 이어받는다
                log.error("택배 취소 결과 처리 실패, 다음 주기에 다시 시도: resultId={}", resultId, e);
            }
        }
    }

    // 결과를 모르는 환불(취소 중, 유예 경과)을 같은 키로 다시 요청한다. 5번 안 되면 확인 필요 (#282)
    public void recoverProcessingRefunds() {
        List<Long> candidates = ordersItemRepository.findRefundRecoveryCandidateIds(OrderItemStatus.CANCEL_PROCESSING,
                LocalDateTime.now().minus(OrderCancelTransactions.RECOVERY_GRACE), RECOVERY_BATCH_SIZE);
        for (Long orderItemId : candidates) {
            try {
                orderCancelTransactions.claimRefundRetry(orderItemId).ifPresent(claim -> {
                    log.info("환불 복구 재요청: orderItemId={}, attemptCount={}", orderItemId, claim.attemptCount());
                    orderCancelRefunder.refund(List.of(claim));
                });
            } catch (RuntimeException e) {
                log.error("환불 복구 실패, 다음 주기에 다시 시도: orderItemId={}", orderItemId, e);
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
