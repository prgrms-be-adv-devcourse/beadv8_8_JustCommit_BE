
package com.justcommit.backend.settlement.client.payment;

import java.math.BigDecimal;

public interface SettlementPayoutClient {

    void distributeSettlement(
            Long settlementId,
            Long sellerId,
            BigDecimal payoutAmount,
            BigDecimal fee
    );
}
