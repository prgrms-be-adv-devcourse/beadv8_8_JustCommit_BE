package com.justcommit.backend.market.client.payment;

import java.math.BigDecimal;

public interface PaymentUseCase {

    // 잔액 부족 · 지갑 없음 · 중복 결제는 예외가 아니라 결과 상태로 돌려준다.
    // 응답 타임아웃은 InternalApiResultUnknownException (같은 orderId로 다시 부르면 결과를 알 수 있다)
    PaymentResult pay(Long orderId, Long buyerId, BigDecimal amount);

    // 확정 실패(4xx) · 응답 타임아웃은 예외로 알리고, 호출한 쪽은 잡지 않고 전파해 취소까지 롤백한다
    PaymentResult refundCancelledItem(Long orderItemId, Long orderId, Long buyerId, BigDecimal amount);

    PaymentResult refundShippingFee(Long sellerOrderId, Long orderId, Long buyerId, BigDecimal amount);
}
