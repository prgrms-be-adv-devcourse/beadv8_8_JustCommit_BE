package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.Charge;
import com.justcommit.backend.payment.domain.ChargeType;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.crypto.PaymentKeyCipher;
import com.justcommit.backend.payment.infrastructure.pg.TossConfirmResponse;
import com.justcommit.backend.payment.infrastructure.repository.ChargeRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmRequest;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmResponse;
import com.justcommit.backend.payment.presentation.dto.ChargeCreateRequest;
import com.justcommit.backend.payment.presentation.dto.ChargeCreateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.justcommit.backend.payment.infrastructure.pg.TossPaymentClient;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class ChargeService {
    private final ChargeRepository chargeRepository;
    private final WalletRepository walletRepository;
    private final TossPaymentClient tossPaymentClient;
    private final WalletTransactionRepository walletTransactionRepository;
    private final PaymentKeyCipher paymentKeyCipher;

    @Transactional
    public ChargeCreateResponse createCharge(Long memberId, ChargeCreateRequest request) {
        // 지갑 조회
        Wallet wallet = walletRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(
                        PaymentErrorCode.WALLET_NOT_FOUND
                ));

        ChargeType chargeType = request.getChargeType() != null
                ? request.getChargeType()
                : ChargeType.TOSS;

        Charge charge = chargeRepository.save(Charge.ready(wallet, chargeType, request.getAmount()));

        return ChargeCreateResponse.from(charge);
    }

    @Transactional
    public ChargeConfirmResponse confirmCharge(Long memberId, Long chargeId, ChargeConfirmRequest request) {

        Wallet wallet = walletRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.WALLET_NOT_FOUND));

        Charge charge = chargeRepository.findByIdAndMemberIdForUpdate(chargeId, memberId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.CHARGE_NOT_FOUND));

        charge.validateReady();
        charge.validateConfirmInfo(request.getOrderId(), request.getAmount());

        // 암호화
        String encryptedPaymentKey =
                paymentKeyCipher.encrypt(request.getPaymentKey());

        // 토스 승인 요청
        TossConfirmResponse tossResponse = tossPaymentClient.confirm(
                request.getPaymentKey(), request.getOrderId(), request.getAmount());

        // 토스 승인 응답 검증
        if (tossResponse == null
                || !"DONE".equals(tossResponse.status())
                || !request.getPaymentKey().equals(tossResponse.paymentKey())
                || !charge.getPgOrderNo().equals(tossResponse.orderId())
                || tossResponse.totalAmount() == null
                || tossResponse.approvedAt() == null
                || charge.getAmount().compareTo(BigDecimal.valueOf(tossResponse.totalAmount())
        ) != 0) {
            throw new BusinessException(
                    PaymentErrorCode.PG_CONFIRM_FAILED
            );
        }
        // 충전 승인 처리
        charge.approve(encryptedPaymentKey, tossResponse.approvedAt().toLocalDateTime());

        // 지갑 잔액 증가
        wallet.credit(charge.getAmount());

        // 거래 내역 기록
        walletTransactionRepository.save(WalletTransaction.chargeIn(wallet, charge));

        return ChargeConfirmResponse.from(charge);

    }
}
