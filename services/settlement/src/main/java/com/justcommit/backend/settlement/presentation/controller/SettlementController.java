package com.justcommit.backend.settlement.presentation.controller;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.settlement.application.SettlementService;
import com.justcommit.backend.settlement.presentation.dto.SettlementResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    @GetMapping
    public ApiResponse<List<SettlementResponse>> getMySettlements(
            @AuthenticationPrincipal AuthMember authMember
    ) {
        List<SettlementResponse> settlements =
                settlementService.getSettlements(authMember.memberId())
                        .stream()
                        .map(SettlementResponse::from)
                        .toList();

        return ApiResponse.ok("정산 내역 조회 성공", settlements);
    }
}