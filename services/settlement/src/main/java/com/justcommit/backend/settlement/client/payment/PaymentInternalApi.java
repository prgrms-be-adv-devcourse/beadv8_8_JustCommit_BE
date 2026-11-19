
package com.justcommit.backend.settlement.client.payment;

import com.justcommit.backend.common.client.InternalApiHeaders;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.math.BigDecimal;

@HttpExchange
public interface PaymentInternalApi {

    @PostExchange("/internal/v1/settlementPayouts")
    void distributeSettlement(
            @RequestHeader(InternalApiHeaders.IDEMPOTENCY_KEY)
            String idempotencyKey,
            @RequestBody SettlementPayoutRequest request
    );

    // payoutAmount = 판매액 - fee - sellerBurdenAmount (0원 · 음수 가능).
    // payment는 sellerOrderId로 배송비 수납 · 환불 중복을 막고, shippingFee · sellerBurdenAmount를 REVENUE로 옮긴다
    record SettlementPayoutRequest(
            Long settlementId,
            Long sellerId,
            Long sellerOrderId,
            BigDecimal payoutAmount,
            BigDecimal fee,
            BigDecimal shippingFee,
            BigDecimal sellerBurdenAmount
    ) {}
}
