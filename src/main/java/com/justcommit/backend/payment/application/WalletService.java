package com.justcommit.backend.payment.application;

import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import com.justcommit.backend.payment.presentation.dto.WalletTransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    public long getBalance(Long memberId) {

        Wallet wallet = walletRepository.findByMemberId(memberId)
                .orElseThrow(() -> new IllegalArgumentException("지갑이 존재하지 않습니다."));

        return wallet.getBalance();
    }

    public Page<WalletTransactionResponse> getTransactions(Long memberId, Pageable pageable) {
        Wallet wallet = walletRepository.findByMemberId(memberId)
                .orElseThrow(() ->
                        new IllegalArgumentException("지갑이 존재하지 않습니다.")
                );

        return walletTransactionRepository
                .findByWallet_Id(wallet.getId(), pageable)
                .map(transaction -> new WalletTransactionResponse(
                        transaction.getId(),
                        transaction.getRefType(),
                        transaction.getRefId(),
                        transaction.getRefOccurredTime(),
                        transaction.getStatus(),
                        transaction.getAmount(),
                        transaction.getBalanceAfter(),
                        transaction.getCreatedAt()
                ));
    }

}
