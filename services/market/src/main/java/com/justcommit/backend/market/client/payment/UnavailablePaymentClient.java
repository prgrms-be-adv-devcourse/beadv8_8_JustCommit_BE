package com.justcommit.backend.market.client.payment;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

// shortcut: payment 내부 API 전환 전 임시 구현이라 호출하면 503, #276에서 내부 API 클라이언트로 교체
@Component
class UnavailablePaymentClient implements PaymentUseCase {

    @Override
    public PaymentResult pay(Long orderId, Long buyerId, BigDecimal amount) {
        throw unavailable();
    }

    @Override
    public PaymentResult refundCancelledItem(Long orderItemId, Long buyerId, BigDecimal amount) {
        throw unavailable();
    }

    @Override
    public PaymentResult refundShippingFee(Long sellerOrderId, Long buyerId, BigDecimal amount) {
        throw unavailable();
    }

    private static BusinessException unavailable() {
        return new BusinessException(CommonErrorCode.INTERNAL_API_UNAVAILABLE);
    }
}
