package com.justcommit.backend.settlement.presentation.dto;

import com.justcommit.backend.settlement.SettlementResult;
import com.justcommit.backend.settlement.domain.SettlementStatus;
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
    // 판매자에게 보여 주는 정산 상태: COMPLETED(정산 완료) / PENDING(정산 대기). FAILED는 다시 시도 중이라 PENDING으로 보여 줌
    private final String status;
    private final String productName;
    private final BigDecimal saleAmount;
    private final BigDecimal fee;
    private final BigDecimal payoutAmount;

    public static SettlementResponse from(SettlementResult result) {
        return new SettlementResponse(
                result.getSettlementId(), result.getSellerOrderId(),
                result.getCompletedAt() == null ? null : result.getCompletedAt().toLocalDate(),
                displayStatus(result.getStatus()),
                result.getItemSummary() == null ? "" : result.getItemSummary(),
                result.getSaleAmount(), result.getFee(), result.getPayoutAmount());
    }

    private static String displayStatus(SettlementStatus status) {
        return status == SettlementStatus.COMPLETED ? "COMPLETED" : "PENDING";
    }
}