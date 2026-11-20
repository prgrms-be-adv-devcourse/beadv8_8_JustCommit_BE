package com.justcommit.backend.payment.application;

import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import com.justcommit.backend.payment.presentation.dto.WalletTransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    // 지갑이 없으면 만들지 않고 잔액 0 (docs/services/payment.md 「정책」 조회)
    public BigDecimal getBalance(Long memberId) {
        return walletRepository.findByMemberId(memberId)
                .map(Wallet::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    // 지갑이 없으면 만들지 않고 빈 목록 (docs/services/payment.md 「정책」 조회)
    public Page<WalletTransactionResponse> getTransactions(Long memberId, RefType refType, Pageable pageable) {
        return walletRepository.findByMemberId(memberId)
                .map(wallet -> findTransactions(wallet.getId(), refType, pageable).map(WalletTransactionResponse::from))
                .orElseGet(() -> Page.empty(pageable));
    }

    private Page<WalletTransaction> findTransactions(Long walletId, RefType refType, Pageable pageable) {
        return (refType == null)
                ? walletTransactionRepository.findByWallet_Id(walletId, pageable)
                : walletTransactionRepository.findByWallet_IdAndRefType(walletId, refType, pageable);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void createIfAbsent(Long memberId) {
        if (walletRepository.findByMemberId(memberId).isPresent()) {
            return;
        }
        try {
            walletRepository.saveAndFlush(Wallet.forMember(memberId));
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 회원 지갑을 만들려다 member_id UK에 걸린 경우 → 이미 생성됨
        }
    }
}
