package com.justcommit.backend.payment.presentation.controller;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.payment.application.ChargeService;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmRequest;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmResponse;
import com.justcommit.backend.payment.presentation.dto.ChargeCreateRequest;
import com.justcommit.backend.payment.presentation.dto.ChargeCreateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wallet/charges")
public class ChargeController {
    private final ChargeService chargeService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ChargeCreateResponse> createCharge( //@AuthenticationPrincipal
            @RequestParam Long memberId,
            @Valid @RequestBody ChargeCreateRequest request
    ) {
        ChargeCreateResponse response = chargeService.createCharge(memberId, request);
        return ApiResponse.created("충전 요청 성공", response);
    }

    @PostMapping("/{chargeId}/confirm")
    public ApiResponse<ChargeConfirmResponse> confirmCharge(
            @RequestParam Long memberId,
            @PathVariable Long chargeId,
            @Valid @RequestBody ChargeConfirmRequest request
    ) {
        return ApiResponse.ok("충전이 완료되었습니다.", chargeService.confirmCharge(memberId, chargeId, request));
    }

}
