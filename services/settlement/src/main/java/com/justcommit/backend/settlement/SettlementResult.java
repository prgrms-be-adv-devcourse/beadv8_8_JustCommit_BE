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
    // 지급 완료 또는 회수 요청 성공 시각. 완료 전 null
    private final LocalDateTime completedAt;
    private final String itemSummary;
}
