package com.justcommit.backend.payment.presentation.controller;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.payment.application.WalletService;
import com.justcommit.backend.payment.presentation.dto.WalletBalanceResponse;
import com.justcommit.backend.payment.presentation.dto.WalletTransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wallet")
public class WalletController {

    private final WalletService walletService;

    @GetMapping
    public ApiResponse<WalletBalanceResponse> getBalance(@RequestParam Long memberId){ //@AuthenticationPrincipal

        BigDecimal balance = walletService.getBalance(memberId);
        return ApiResponse.ok("예치금 조회 성공", new WalletBalanceResponse(balance));
    }

    @GetMapping("/transactions")
    public ApiResponse<Page<WalletTransactionResponse>> getTransactions(@RequestParam Long memberId, Pageable pageable) {
        return ApiResponse.ok("거래 내역 조회 성공", walletService.getTransactions(memberId, pageable));
    }
}
