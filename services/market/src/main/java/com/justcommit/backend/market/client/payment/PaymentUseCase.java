package com.justcommit.backend.market.client.payment;

import java.math.BigDecimal;

public interface PaymentUseCase {

    // 결제 결과를 PaymentOutcome으로 분류해 돌려주고 예외를 던지지 않는다 (ADR 0023).
    // 같은 orderId(Idempotency-Key)로 다시 부르면 payment는 처음 결과를 돌려준다 (ALREADY_PAID)
    PaymentOutcome pay(Long orderId, Long buyerId, BigDecimal amount);

    // 확정 실패(4xx) · 응답 타임아웃은 예외로 알리고, 호출한 쪽은 잡지 않고 전파해 취소까지 롤백한다
    PaymentResult refundCancelledItem(Long orderItemId, Long orderId, Long buyerId, BigDecimal amount);

    PaymentResult refundShippingFee(Long sellerOrderId, Long orderId, Long buyerId, BigDecimal amount);
}
