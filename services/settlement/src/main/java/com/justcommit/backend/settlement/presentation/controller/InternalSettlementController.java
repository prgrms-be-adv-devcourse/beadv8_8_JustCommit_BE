package com.justcommit.backend.settlement.presentation.controller;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.settlement.application.SettlementService;
import com.justcommit.backend.settlement.presentation.dto.SettlementTimeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/settlements")
public class InternalSettlementController {

    private final SettlementService settlementService;

    @GetMapping("/sellerOrders/{sellerOrderId}")
    public ApiResponse<SettlementTimeResponse> getSettlementTime(
            @PathVariable Long sellerOrderId
    ) {
        // market이 읽는 응답 칸 이름은 settledAt 그대로 두고, 값은 completedAt (지급 전이면 null)
        LocalDateTime completedAt = settlementService
                .findCompletedAtBySellerOrderId(sellerOrderId)
                .orElse(null);

        return ApiResponse.ok(
                "정산 완료 시각 조회 성공",
                new SettlementTimeResponse(sellerOrderId, completedAt)
        );
    }
}
