package com.justcommit.backend.market.client.payment;

import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.market.client.payment.PaymentApi.PaymentRequest;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class PaymentClient implements PaymentUseCase {

    private final PaymentApi paymentApi;

    @Override
    public PaymentResult pay(Long orderId, Long buyerId, BigDecimal amount) {
        try {
            return paymentApi.pay(orderId, new PaymentRequest(orderId, buyerId, amount)).data();
        } catch (InternalApiErrorException e) {
            if ("INSUFFICIENT_BALANCE".equals(e.code())) {
                return PaymentResult.insufficientBalance(null);
            }
            if ("WALLET_NOT_FOUND".equals(e.code())) {
                return PaymentResult.walletNotFound();
            }
            throw e;
        }
    }

    @Override
    public PaymentResult refundCancelledItem(Long orderItemId, Long orderId, Long buyerId, BigDecimal amount) {
        return paymentApi.refundOrderItem(orderItemId, orderItemId, new PaymentRequest(orderId, buyerId, amount)).data();
    }

    @Override
    public PaymentResult refundShippingFee(Long sellerOrderId, Long orderId, Long buyerId, BigDecimal amount) {
        return paymentApi.refundShippingFee(sellerOrderId, sellerOrderId, new PaymentRequest(orderId, buyerId, amount)).data();
    }
}
