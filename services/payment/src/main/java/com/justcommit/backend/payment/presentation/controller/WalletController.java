package com.justcommit.backend.payment.presentation.controller;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.payment.application.WalletService;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.presentation.dto.WalletBalanceResponse;
import com.justcommit.backend.payment.presentation.dto.WalletTransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wallet")
public class WalletController {

    private final WalletService walletService;

    @GetMapping
    public ApiResponse<WalletBalanceResponse> getBalance(@AuthenticationPrincipal AuthMember authMember){

        BigDecimal balance = walletService.getBalance(authMember.memberId());
        return ApiResponse.ok("예치금 조회 성공", new WalletBalanceResponse(balance));
    }

    @GetMapping("/transactions")
    public ApiResponse<Page<WalletTransactionResponse>> getTransactions(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestParam(required = false) RefType refType,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok("거래 내역 조회 성공", walletService.getTransactions(authMember.memberId(), refType, pageable));
    }
}
