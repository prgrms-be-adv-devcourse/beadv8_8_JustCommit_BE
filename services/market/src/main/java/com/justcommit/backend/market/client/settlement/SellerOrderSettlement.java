package com.justcommit.backend.market.client.settlement;

import java.time.LocalDateTime;

// status: COMPLETED · NOT_SETTLED · UNKNOWN(settlement 장애)
public record SellerOrderSettlement(String status, LocalDateTime settledAt) {

    public static final SellerOrderSettlement UNKNOWN = new SellerOrderSettlement("UNKNOWN", null);

    // settledAt은 지급이 끝난 정산에만 오고 PENDING · FAILED는 null이다
    public static SellerOrderSettlement of(LocalDateTime settledAt) {
        return new SellerOrderSettlement(settledAt == null ? "NOT_SETTLED" : "COMPLETED", settledAt);
    }
}
