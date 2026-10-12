package com.justcommit.backend.settlement;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class SettlementResult {

    private final Long settlementId;
    private final Long sellerOrderId;
    private final BigDecimal saleAmount;
    private final BigDecimal fee;
    private final BigDecimal payoutAmount;
    private final LocalDateTime settledAt;
}
