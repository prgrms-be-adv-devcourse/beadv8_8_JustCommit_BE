package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.Charge;
import com.justcommit.backend.payment.domain.ChargeType;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.repository.ChargeRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.presentation.dto.ChargeCreateRequest;
import com.justcommit.backend.payment.presentation.dto.ChargeCreateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class ChargeService {
    private final ChargeRepository chargeRepository;
    private final WalletRepository walletRepository;

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
}
