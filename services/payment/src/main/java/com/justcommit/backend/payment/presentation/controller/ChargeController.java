package com.justcommit.backend.payment.presentation.controller;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.payment.application.ChargeConfirmFacade;
import com.justcommit.backend.payment.application.ChargeService;
import com.justcommit.backend.payment.domain.ChargeStatus;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmRequest;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmResponse;
import com.justcommit.backend.payment.presentation.dto.ChargeCreateRequest;
import com.justcommit.backend.payment.presentation.dto.ChargeCreateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wallet/charges")
public class ChargeController {
    private final ChargeService chargeService;
    private final ChargeConfirmFacade chargeConfirmFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ChargeCreateResponse> createCharge(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody ChargeCreateRequest request
    ) {
        ChargeCreateResponse response = chargeService.createCharge(authMember.memberId(), request);
        return ApiResponse.created("충전 요청 성공", response);
    }

    @PostMapping("/{chargeId}/confirm")
    public ApiResponse<ChargeConfirmResponse> confirmCharge(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long chargeId,
            @Valid @RequestBody ChargeConfirmRequest request
    ) {
        ChargeConfirmResponse response = chargeConfirmFacade.confirmCharge(authMember.memberId(), chargeId, request);
        String message = response.getStatus() == ChargeStatus.APPROVED
                ? "충전이 완료되었습니다."
                : "충전 처리 중입니다.";
        return ApiResponse.ok(message, response);
    }

}
