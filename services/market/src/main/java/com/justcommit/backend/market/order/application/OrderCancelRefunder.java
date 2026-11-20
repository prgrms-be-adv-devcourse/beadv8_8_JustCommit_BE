package com.justcommit.backend.market.order.application;

import com.justcommit.backend.market.client.payment.PaymentUseCase;
import com.justcommit.backend.market.client.payment.RefundOutcome;
import com.justcommit.backend.market.order.application.OrderCancelTransactions.RefundClaim;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 선점한 환불 건을 트랜잭션 밖에서 payment에 요청하고 결과를 반영한다 (#282).
// 같은 판매자 주문의 묶음을 받으며, 배송비는 한 번만 요청한다. 같은 키라 다시 불러도 payment는 ALREADY_REFUNDED로 답한다
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCancelRefunder {

    static final String SHIPPING_FEE_PREFIX = "SHIPPING_FEE_";

    private final PaymentUseCase paymentUseCase;
    private final OrderCancelTransactions orderCancelTransactions;

    public void refund(List<RefundClaim> claims) {
        if (claims.isEmpty()) {
            return;
        }
        Map<Long, RefundOutcome> itemOutcomes = new LinkedHashMap<>();
        for (RefundClaim claim : claims) {
            itemOutcomes.put(claim.orderItemId(), paymentUseCase.refundCancelledItem(
                    claim.orderItemId(), claim.orderId(), claim.buyerId(), claim.price()));
        }
        RefundOutcome shippingOutcome = claims.stream()
                .filter(claim -> claim.shippingFee() != null)
                .findFirst()
                .map(claim -> markShippingFee(paymentUseCase.refundShippingFee(
                        claim.sellerOrderId(), claim.orderId(), claim.buyerId(), claim.shippingFee())))
                .orElse(null);

        for (RefundClaim claim : claims) {
            // 전부 취소인 판매자 주문은 배송비 환불까지 확정돼야 취소 완료
            RefundOutcome outcome = itemOutcomes.get(claim.orderItemId())
                    .and(claim.shippingFee() == null ? null : shippingOutcome);
            try {
                orderCancelTransactions.applyRefundOutcome(claim, outcome);
            } catch (RuntimeException e) {
                // 주문상품은 취소 중으로 남아 유예가 지나면 복구가 같은 키로 다시 요청한다
                log.error("환불 결과 반영 실패, 복구가 다시 시도: orderItemId={}, outcome={}",
                        claim.orderItemId(), outcome.type(), e);
            }
        }
    }

    // 배송비 환불의 실패 원인은 last_error에서 상품 금액 환불과 구분되게 앞에 SHIPPING_FEE_를 붙인다
    // (예: SHIPPING_FEE_HTTP_400_INVALID_AMOUNT, SHIPPING_FEE_TIMEOUT)
    private static RefundOutcome markShippingFee(RefundOutcome outcome) {
        if (outcome.reason() == null) {
            return outcome;
        }
        return new RefundOutcome(outcome.type(), SHIPPING_FEE_PREFIX + outcome.reason());
    }
}
