package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.PaymentQuery;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
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
public class WalletService implements PaymentQuery {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    @Override
    public BigDecimal getBalance(Long memberId) {
        return getWallet(memberId).getBalance();
    }

    public Page<WalletTransactionResponse> getTransactions(Long memberId, RefType refType, Pageable pageable) {
        Wallet wallet = getWallet(memberId);

        Page<WalletTransaction> page = (refType == null)
                ? walletTransactionRepository.findByWallet_Id(wallet.getId(), pageable)
                : walletTransactionRepository.findByWallet_IdAndRefType(wallet.getId(), refType, pageable);

        return page.map(WalletTransactionResponse::from);
    }

    private Wallet getWallet(Long memberId) {
        return walletRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.WALLET_NOT_FOUND));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void createIfAbsent(Long memberId) {
        if (walletRepository.findByMemberId(memberId).isPresent()) {
            return;
        }
        try {
            walletRepository.saveAndFlush(new Wallet(memberId));
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 회원 지갑을 만들려다 member_id UK에 걸린 경우 → 이미 생성됨
        }
    }
}
