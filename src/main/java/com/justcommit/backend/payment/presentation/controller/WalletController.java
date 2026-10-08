package com.justcommit.backend.payment.presentation.controller;

import com.justcommit.backend.payment.PaymentQuery;
import com.justcommit.backend.payment.presentation.dto.WalletBalanceResponse;
import com.justcommit.backend.payment.presentation.dto.WalletTransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/wallet")
public class WalletController {

    private final PaymentQuery paymentQuery;

    @GetMapping
    public ResponseEntity<WalletBalanceResponse> getBalance(@RequestParam Long memberId){

        long balance = paymentQuery.getBalance(memberId);
        return ResponseEntity.ok(new WalletBalanceResponse(balance));
    }

    @GetMapping("/transactions")
    public ResponseEntity<Page<WalletTransactionResponse>> getTransactions(@RequestParam Long memberId, Pageable pageable) {
        return ResponseEntity.ok(paymentQuery.getTransactions(memberId, pageable));
    }
}
