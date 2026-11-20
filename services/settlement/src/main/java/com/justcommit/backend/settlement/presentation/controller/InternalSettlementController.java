package com.justcommit.backend.settlement.presentation.controller;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.settlement.application.SettlementService;
import com.justcommit.backend.settlement.application.withdrawal.WithdrawalCheckService;
import com.justcommit.backend.settlement.presentation.dto.SettlementTimeResponse;
import com.justcommit.backend.settlement.presentation.dto.WithdrawalCheckResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

// 서비스 간 내부 API (Gateway가 라우팅하지 않음). X-Internal-Key는 공통 필터가 확인한다 → docs/reference/internal-apis.md
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/settlements")
public class InternalSettlementController {

    private final SettlementService settlementService;
    private final WithdrawalCheckService withdrawalCheckService;

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

    // member가 탈퇴 전에 완료되지 않은 정산(PENDING, FAILED)을 확인. 정산 기록이 없는 회원도 200 (차단 없음)
    @GetMapping("/members/{memberId}/withdrawalCheck")
    public ApiResponse<WithdrawalCheckResponse> withdrawalCheck(@PathVariable Long memberId) {
        return ApiResponse.ok("탈퇴 확인 결과를 조회했습니다.", withdrawalCheckService.check(memberId));
    }
}
