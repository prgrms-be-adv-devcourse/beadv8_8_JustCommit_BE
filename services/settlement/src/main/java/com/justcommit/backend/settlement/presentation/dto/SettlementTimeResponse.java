package com.justcommit.backend.settlement.presentation.dto;

import java.time.LocalDateTime;

public record SettlementTimeResponse(
        Long sellerOrderId,
        LocalDateTime settledAt
) {
}
