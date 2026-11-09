package com.justcommit.backend.settlement.presentation.dto;

import com.justcommit.backend.settlement.SettlementResult;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@AllArgsConstructor
public class SettlementResponse {

    private final Long settlementId;
    private final Long sellerOrderId;
    private final LocalDate date;
    private final String productName;
    private final BigDecimal saleAmount;
    private final BigDecimal fee;
    private final BigDecimal payoutAmount;

    public static SettlementResponse from(SettlementResult result) {
        return new SettlementResponse(
                result.getSettlementId(), result.getSellerOrderId(),
                result.getSettledAt().toLocalDate(),
                result.getItemSummary() == null ? "" : result.getItemSummary(),
                result.getSaleAmount(), result.getFee(), result.getPayoutAmount());
    }
}