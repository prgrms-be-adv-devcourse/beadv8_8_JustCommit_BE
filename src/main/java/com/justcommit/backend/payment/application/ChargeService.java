package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.Charge;
import com.justcommit.backend.payment.domain.ChargeType;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
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

@Service
@RequiredArgsConstructor
public class ChargeService {
    private final ChargeRepository chargeRepository;
    private final WalletRepository walletRepository;
    private final TossPaymentClient tossPaymentClient;
    private final WalletTransactionRepository walletTransactionRepository;

    @Transactional
    public ChargeCreateResponse createCharge(
            Long memberId,
            ChargeCreateRequest request
    ) {
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
        // 내 충전 요청인지 확인하며 조회
        Charge charge = chargeRepository.findByIdAndWallet_MemberId(chargeId, memberId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.CHARGE_NOT_FOUND));

        charge.validateReady();
        // 프론트가 보낸 값과 서버에 저장된 값 대조 (금액 위변조 방지)
        charge.validateConfirmInfo(request.getOrderId(), request.getAmount());

        // 토스 승인 요청
        TossConfirmResponse tossResponse = tossPaymentClient.confirm(
                request.getPaymentKey(), request.getOrderId(), request.getAmount());

        // 충전 승인 처리
        charge.approve(tossResponse.paymentKey(), tossResponse.approvedAt().toLocalDateTime());

        // 지갑 입금
        Wallet wallet = charge.getWallet();
        wallet.credit(charge.getAmount());

        // 거래 내역 기록
        walletTransactionRepository.save(WalletTransaction.chargeIn(wallet, charge));

        return ChargeConfirmResponse.from(charge);
    }
}
