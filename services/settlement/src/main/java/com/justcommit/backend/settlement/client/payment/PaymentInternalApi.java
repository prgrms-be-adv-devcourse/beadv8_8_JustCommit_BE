
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

    record SettlementPayoutRequest(
            Long settlementId,
            Long sellerId,
            BigDecimal payoutAmount,
            BigDecimal fee
    ) {}
}
