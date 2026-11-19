package com.justcommit.backend.market.client.payment;

import java.math.BigDecimal;

public interface PaymentUseCase {

    // 결제 결과를 PaymentOutcome으로 분류해 돌려주고 예외를 던지지 않는다 (ADR 0023).
    // 같은 orderId(Idempotency-Key)로 다시 부르면 payment는 처음 결과를 돌려준다 (ALREADY_PAID)
    PaymentOutcome pay(Long orderId, Long buyerId, BigDecimal amount);

    // 환불 결과를 RefundOutcome으로 분류해 돌려주고 예외를 던지지 않는다 (#282).
    // 같은 키(orderItemId, sellerOrderId)로 다시 부르면 payment는 처음 결과를 돌려준다 (ALREADY_REFUNDED)
    RefundOutcome refundCancelledItem(Long orderItemId, Long orderId, Long buyerId, BigDecimal amount);

    RefundOutcome refundShippingFee(Long sellerOrderId, Long orderId, Long buyerId, BigDecimal amount);
}
