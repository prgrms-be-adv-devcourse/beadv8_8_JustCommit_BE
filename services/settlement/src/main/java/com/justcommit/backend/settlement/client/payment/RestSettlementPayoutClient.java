
package com.justcommit.backend.settlement.client.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class RestSettlementPayoutClient implements SettlementPayoutClient {

    private final PaymentInternalApi paymentInternalApi;

    @Override
    public void distributeSettlement(
            Long settlementId,
            Long sellerId,
            BigDecimal payoutAmount,
            BigDecimal fee
    ) {
        PaymentInternalApi.SettlementPayoutRequest request =
                new PaymentInternalApi.SettlementPayoutRequest(
                        settlementId,
                        sellerId,
                        payoutAmount,
                        fee
                );

        paymentInternalApi.distributeSettlement(
                String.valueOf(settlementId),
                request
        );
    }
}
