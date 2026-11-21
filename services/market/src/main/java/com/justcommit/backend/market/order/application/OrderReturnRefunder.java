package com.justcommit.backend.market.order.application;

import com.justcommit.backend.market.client.payment.PaymentUseCase;
import com.justcommit.backend.market.client.payment.RefundOutcome;
import com.justcommit.backend.market.order.application.OrderReturnRefundTransactions.ReturnRefundClaim;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 선점한 반품 환불 한 건을 트랜잭션 밖에서 payment에 요청하고 결과를 반영한다 (#384, #282 OrderCancelRefunder와 같은 방식).
// 상품 금액은 orderItemId, 배송비는 sellerOrderId가 키라 다시 불러도 payment는 ALREADY_REFUNDED로 답한다
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderReturnRefunder {

    private final PaymentUseCase paymentUseCase;
    private final OrderReturnRefundTransactions orderReturnRefundTransactions;

    public void refund(ReturnRefundClaim claim) {
        RefundOutcome outcome = paymentUseCase.refundCancelledItem(
                claim.orderItemId(), claim.orderId(), claim.buyerId(), claim.price());
        if (claim.shippingFee() != null) {
            // 전부 반품이면 배송비 환불까지 확정돼야 반품 완료
            outcome = outcome.and(markShippingFee(paymentUseCase.refundShippingFee(
                    claim.sellerOrderId(), claim.orderId(), claim.buyerId(), claim.shippingFee())));
        }
        try {
            orderReturnRefundTransactions.applyRefundOutcome(claim, outcome);
        } catch (RuntimeException e) {
            // 주문상품은 반품 환불 중으로 남아 유예가 지나면 복구가 같은 키로 다시 요청한다
            log.error("반품 환불 결과 반영 실패, 복구가 다시 시도: orderItemId={}, outcome={}",
                    claim.orderItemId(), outcome.type(), e);
        }
    }

    // 배송비 환불의 실패 원인은 last_error에서 상품 금액 환불과 구분되게 앞에 SHIPPING_FEE_를 붙인다 (취소 환불과 같음)
    private static RefundOutcome markShippingFee(RefundOutcome outcome) {
        if (outcome.reason() == null) {
            return outcome;
        }
        return new RefundOutcome(outcome.type(), OrderCancelRefunder.SHIPPING_FEE_PREFIX + outcome.reason());
    }
}
