package com.justcommit.backend.payment.presentation.internal.dto;

import java.math.BigDecimal;

public record SettlementPayoutRequest(Long settlementId, Long sellerId, BigDecimal payoutAmount, BigDecimal fee) {
}
