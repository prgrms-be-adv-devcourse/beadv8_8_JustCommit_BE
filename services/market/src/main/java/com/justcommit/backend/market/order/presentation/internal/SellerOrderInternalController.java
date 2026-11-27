package com.justcommit.backend.market.order.presentation.internal;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.market.order.application.SettlementTargetQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

// 판매자 주문 내부 API (Gateway가 라우팅하지 않음, X-Internal-Key는 공통 필터가 확인). 규칙 → docs/reference/internal-apis.md
@RestController
@RequestMapping("/internal/v1/sellerOrders")
@RequiredArgsConstructor
public class SellerOrderInternalController {

    static final int MAX_SIZE = 100;

    private final SettlementTargetQueryService settlementTargetQueryService;

    // settlement 대사용 정산 대상 판매자 주문 조회 (#657, 읽기 전용). confirmedAt이 from 이상 to 미만,
    // cursor(마지막 sellerOrderId) 다음부터 sellerOrderId 오름차순. size는 1~100(생략하면 100), from > to는 400
    @GetMapping("/settlementTargets")
    public ApiResponse<SettlementTargetResponse> settlementTargets(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "" + MAX_SIZE) int size) {
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(CommonErrorCode.INVALID_FIELD_ERROR, "size: 1 이상 100 이하여야 합니다.");
        }
        if (from.isAfter(to)) {
            throw new BusinessException(CommonErrorCode.INVALID_FIELD_ERROR, "from: to보다 늦을 수 없습니다.");
        }
        if (cursor != null && cursor < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_FIELD_ERROR, "cursor: 1 이상이어야 합니다.");
        }
        return ApiResponse.ok("정산 대상 판매자 주문을 조회했습니다.", SettlementTargetResponse.from(
                settlementTargetQueryService.find(from, to, cursor, size)));
    }
}
