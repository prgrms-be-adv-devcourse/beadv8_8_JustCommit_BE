package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.MemberQuery;
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

/**
 * 사용자->시스템 순서로 지갑 잠금
 */

@Service
@RequiredArgsConstructor
public class PaymentService implements PaymentUseCase {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final MemberQuery memberQuery; // 시스템 HOLDING

    @Override
    @Transactional
    public PaymentResult pay(Long orderId, Long buyerId, BigDecimal amount) {
        validateRequest(orderId, buyerId, amount);

        Optional<Wallet> found = walletRepository.findByMemberIdForUpdate(buyerId);
        if (found.isEmpty()) {
            return PaymentResult.walletNotFound();
        }
        Wallet buyerWallet = found.get();

        if (walletTransactionRepository.existsByWallet_IdAndRefTypeAndRefId(buyerWallet.getId(), RefType.PAYMENT, orderId)) {
            return PaymentResult.alreadyPaid(buyerWallet.getBalance());
        }

        if (!buyerWallet.hasEnoughBalance(amount)) {
            return PaymentResult.insufficientBalance(buyerWallet.getBalance());
        }

        buyerWallet.debit(amount);
        LocalDateTime now = LocalDateTime.now(); // 구매자 출금·HOLDING 입금을 시각으로 기록

        walletTransactionRepository.save(
                WalletTransaction.paymentOut(buyerWallet, orderId, amount, now));


        Long holdingMemberId = memberQuery.getHoldingMemberId();
        Wallet holdingWallet = walletRepository
                .findByMemberIdForUpdate(holdingMemberId)
                .orElseThrow(() -> new BusinessException(
                        PaymentErrorCode.WALLET_NOT_FOUND
                ));

        holdingWallet.credit(amount);
        walletTransactionRepository.save(
                WalletTransaction.holdingIn(holdingWallet, orderId, amount, now));


        return PaymentResult.paid(buyerWallet.getBalance());
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