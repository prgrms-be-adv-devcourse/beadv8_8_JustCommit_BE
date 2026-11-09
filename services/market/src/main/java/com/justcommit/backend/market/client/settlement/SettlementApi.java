package com.justcommit.backend.market.client.settlement;

import com.justcommit.backend.common.response.ApiResponse;
import java.time.LocalDateTime;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange("/internal/v1/settlements")
public interface SettlementApi {

    // 정산 전이면 200 + settledAt null
    @GetExchange("/sellerOrders/{sellerOrderId}")
    ApiResponse<SettledAt> getSettledAt(@PathVariable Long sellerOrderId);

    record SettledAt(Long sellerOrderId, LocalDateTime settledAt) {
    }
}
