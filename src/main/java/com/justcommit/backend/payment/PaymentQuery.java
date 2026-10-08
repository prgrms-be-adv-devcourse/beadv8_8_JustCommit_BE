package com.justcommit.backend.payment;

import com.justcommit.backend.payment.presentation.dto.WalletTransactionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PaymentQuery {
    long getBalance(Long memberId);

    Page<WalletTransactionResponse> getTransactions(Long memberId, Pageable pageable);
}
