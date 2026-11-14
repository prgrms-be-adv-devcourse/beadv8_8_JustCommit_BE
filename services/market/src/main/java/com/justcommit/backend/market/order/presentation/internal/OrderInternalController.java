package com.justcommit.backend.market.order.presentation.internal;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.market.order.application.OrderWithdrawalCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 서비스 간 내부 API (Gateway가 라우팅하지 않음, X-Internal-Key는 공통 필터가 확인). 규칙 → docs/reference/internal-apis.md
@RestController
@RequestMapping("/internal/v1/orders")
@RequiredArgsConstructor
public class OrderInternalController {

    private final OrderWithdrawalCheckService withdrawalCheckService;

    // member가 탈퇴 전에 진행 중 주문(구매 · 판매)이 있는지 확인. 주문이 없는 회원도 200 (차단 없음)
    @GetMapping("/members/{memberId}/withdrawalCheck")
    public ApiResponse<WithdrawalCheckResponse> withdrawalCheck(@PathVariable Long memberId) {
        return ApiResponse.ok("탈퇴 확인 결과를 조회했습니다.", withdrawalCheckService.check(memberId));
    }
}
