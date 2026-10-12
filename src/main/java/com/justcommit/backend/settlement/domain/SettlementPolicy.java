package com.justcommit.backend.settlement.domain;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.math.RoundingMode;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SettlementPolicy {

    private static final BigDecimal FEE_RATE = new BigDecimal("0.05");

    // 수수료 계산
    public static BigDecimal calculateFee(BigDecimal saleAmount) {
        return saleAmount.multiply(FEE_RATE)
                .setScale(0, RoundingMode.DOWN);
    }
}