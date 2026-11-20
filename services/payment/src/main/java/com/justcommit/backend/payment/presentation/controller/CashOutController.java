package com.justcommit.backend.payment.presentation.controller;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.payment.application.cashout.CashOutFacade;
import com.justcommit.backend.payment.application.cashout.CashOutService;
import com.justcommit.backend.payment.presentation.dto.CashOutHistoryResponse;
import com.justcommit.backend.payment.presentation.dto.CashOutRequest;
import com.justcommit.backend.payment.presentation.dto.CashOutResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wallet/cashOuts")
public class CashOutController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CashOutFacade cashOutFacade;
    private final CashOutService cashOutService;

    // 인출 신청. 같은 Idempotency-Key로 다시 보내면 처음 결과 (금액이 다르면 409)
    // 지급이 실패해도 200 + status PENDING (차감은 유지, 재시도, 복원은 #389)
    @PostMapping
    public ApiResponse<CashOutResponse> requestCashOut(@AuthenticationPrincipal AuthMember authMember,
                                                       @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                                       @Valid @RequestBody CashOutRequest request) {
        return ApiResponse.ok("인출을 신청했습니다.", CashOutResponse.from(
                cashOutFacade.requestCashOut(authMember.memberId(), idempotencyKey, request.amount())));
    }

    @GetMapping
    public ApiResponse<Page<CashOutHistoryResponse>> getCashOuts(
            @AuthenticationPrincipal AuthMember authMember,
            @PageableDefault(size = 20, sort = "requestedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok("인출 내역 조회 성공", cashOutService.getCashOuts(authMember.memberId(), pageable));
    }
}
