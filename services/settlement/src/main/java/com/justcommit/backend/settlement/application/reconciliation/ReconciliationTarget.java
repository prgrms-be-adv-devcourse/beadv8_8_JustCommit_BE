package com.justcommit.backend.settlement.application.reconciliation;

import com.justcommit.backend.settlement.domain.SettlementStatus;

import java.math.BigDecimal;

// 정산 대사 대상 정산. 기대 원장 계산에 필요한 값만 읽는다
public record ReconciliationTarget(
        Long settlementId,
        Long sellerId,
        SettlementStatus status,
        BigDecimal fee,
        BigDecimal sellerBurdenAmount,
        BigDecimal payoutAmount
) {
}
