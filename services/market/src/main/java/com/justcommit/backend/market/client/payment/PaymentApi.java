package com.justcommit.backend.market.client.payment;

import static com.justcommit.backend.common.client.InternalApiHeaders.IDEMPOTENCY_KEY;

import com.justcommit.backend.common.response.ApiResponse;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

// 같은 Idempotency-Key로 다시 오면 payment는 처음 결과를 돌려준다 (ALREADY_PAID · ALREADY_REFUNDED)
@HttpExchange("/internal/v1")
public interface PaymentApi {

    @PostExchange("/payments")
    ApiResponse<PaymentResult> pay(@RequestHeader(IDEMPOTENCY_KEY) Long orderId, @RequestBody PaymentRequest request);

    @PostExchange("/refunds/orderItems/{orderItemId}")
    ApiResponse<PaymentResult> refundOrderItem(@RequestHeader(IDEMPOTENCY_KEY) Long idempotencyKey,
                                               @PathVariable Long orderItemId,
                                               @RequestBody PaymentRequest request);

    @PostExchange("/refunds/shippingFees/{sellerOrderId}")
    ApiResponse<PaymentResult> refundShippingFee(@RequestHeader(IDEMPOTENCY_KEY) Long idempotencyKey,
                                                 @PathVariable Long sellerOrderId,
                                                 @RequestBody PaymentRequest request);

    // 환불에도 orderId를 보내 payment가 원래 결제(충전금 · 정산금 비율)를 찾게 한다
    record PaymentRequest(Long orderId, Long buyerId, BigDecimal amount) {
    }
}
