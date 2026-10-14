package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.PaymentResult;
import com.justcommit.backend.payment.PaymentUseCase;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentService implements PaymentUseCase {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;


    @Override
    @Transactional
    public PaymentResult pay(Long orderId, Long buyerId, BigDecimal amount) {
        validateRequest(orderId, buyerId, amount);

        Optional<Wallet> found = walletRepository.findByMemberIdForUpdate(buyerId);
        if (found.isEmpty()) {
            return PaymentResult.walletNotFound();
        }
        Wallet wallet = found.get();

        if (walletTransactionRepository.existsByWallet_IdAndRefTypeAndRefId(wallet.getId(), RefType.PAYMENT, orderId)) {
            return PaymentResult.alreadyPaid(wallet.getBalance());
        }

        if (!wallet.hasEnoughBalance(amount)) {
            return PaymentResult.insufficientBalance(wallet.getBalance());
        }

        wallet.debit(amount);
        walletTransactionRepository.save(
                WalletTransaction.paymentOut(wallet, orderId, amount, LocalDateTime.now()));

        return PaymentResult.paid(wallet.getBalance());
    }

    @Override
    @Transactional
    public void depositSettlement(Long settlementId, Long sellerId, BigDecimal amount) {
        validateRequest(settlementId, sellerId, amount);

        Wallet wallet = walletRepository
                .findByMemberIdForUpdate(sellerId)
                .orElseThrow(() -> new BusinessException(
                        PaymentErrorCode.WALLET_NOT_FOUND
                ));

        if (walletTransactionRepository.existsByWallet_IdAndRefTypeAndRefId(
                wallet.getId(), RefType.SETTLEMENT, settlementId)) {
            return;
        }

        wallet.credit(amount);

        walletTransactionRepository.save(
                WalletTransaction.settlementIn(
                        wallet, settlementId, amount, LocalDateTime.now()
                )
        );
    }

    private void validateRequest(Long orderId, Long buyerId, BigDecimal amount) {
        if (orderId == null || buyerId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT, "주문 결제 요청 값이 올바르지 않습니다.");
        }
    }
}