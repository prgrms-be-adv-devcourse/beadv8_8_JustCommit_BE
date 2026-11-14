package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.Charge;
import com.justcommit.backend.payment.domain.ChargeType;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.crypto.PaymentKeyCipher;
import com.justcommit.backend.payment.infrastructure.repository.ChargeRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmRequest;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmResponse;
import com.justcommit.backend.payment.presentation.dto.ChargeCreateRequest;
import com.justcommit.backend.payment.presentation.dto.ChargeCreateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ChargeService {
    private final ChargeRepository chargeRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final PaymentKeyCipher paymentKeyCipher;
    private final WalletService walletService;

    @Transactional
    public ChargeCreateResponse createCharge(Long memberId, ChargeCreateRequest request) {
        Wallet wallet = walletRepository.findByMemberId(memberId)
                .orElseGet(() -> {
                    walletService.createIfAbsent(memberId);
                    return walletRepository.findByMemberId(memberId)
                            .orElseThrow(() -> new BusinessException(PaymentErrorCode.WALLET_NOT_FOUND));
                });

        ChargeType chargeType = request.getChargeType() != null
                ? request.getChargeType()
                : ChargeType.TOSS;

        Charge charge = chargeRepository.save(Charge.ready(wallet, chargeType, request.getAmount()));

        return ChargeCreateResponse.from(charge);
    }

    // TX1: 충전 건만 잠그고 READY → PENDING 후 커밋. 지갑은 잠그지 않는다
    @Transactional
    public ChargeConfirmResponse startConfirm(Long memberId, Long chargeId, ChargeConfirmRequest request) {
        Charge charge = chargeRepository.findByIdAndMemberIdForUpdate(chargeId, memberId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.CHARGE_NOT_FOUND));

        charge.validateConfirmInfo(request.getOrderId(), request.getAmount());
        charge.startConfirm(paymentKeyCipher.encrypt(request.getPaymentKey()));

        return ChargeConfirmResponse.from(charge);
    }

    // TX2: 토스 승인 성공 → APPROVED + 적립 + 거래 내역. 잠금 순서는 지갑 → 충전
    @Transactional
    public ChargeConfirmResponse completeConfirm(Long chargeId, LocalDateTime approvedAt) {
        Long memberId = chargeRepository.findMemberIdById(chargeId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.CHARGE_NOT_FOUND));

        Wallet wallet = walletRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.WALLET_NOT_FOUND));
        Charge charge = chargeRepository.findByIdAndMemberIdForUpdate(chargeId, memberId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.CHARGE_NOT_FOUND));

        charge.completeConfirm(approvedAt);
        wallet.credit(charge.getAmount());
        walletTransactionRepository.save(WalletTransaction.chargeIn(wallet, charge));

        return ChargeConfirmResponse.from(charge);
    }

    // TX2: 토스 거절 → FAILED
    @Transactional
    public void fail(Long chargeId) {
        Long memberId = chargeRepository.findMemberIdById(chargeId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.CHARGE_NOT_FOUND));
        Charge charge = chargeRepository.findByIdAndMemberIdForUpdate(chargeId, memberId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.CHARGE_NOT_FOUND));

        charge.fail();
    }
}
