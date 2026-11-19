package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.application.recovery.ChargeRecoveryProperties;
import com.justcommit.backend.payment.domain.Charge;
import com.justcommit.backend.payment.domain.ChargeType;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.crypto.PaymentKeyCipher;
import com.justcommit.backend.payment.infrastructure.repository.ChargeRecoveryTarget;
import com.justcommit.backend.payment.infrastructure.repository.ChargeRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmRequest;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmResponse;
import com.justcommit.backend.payment.presentation.dto.ChargeCreateRequest;
import com.justcommit.backend.payment.presentation.dto.ChargeCreateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChargeService {
    private final ChargeRepository chargeRepository;
    private final WalletRepository walletRepository;
    private final PaymentKeyCipher paymentKeyCipher;
    private final WalletService walletService;
    private final MemberWalletDepositor memberWalletDepositor;
    private final ChargeRecoveryProperties recoveryProperties;
    private final Clock clock;

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
        // 결과를 모른 채 남으면 복구 스케줄러가 이 시각부터 토스 조회로 확정한다
        LocalDateTime firstLookupAt = LocalDateTime.now(clock).plus(recoveryProperties.firstDelay());
        charge.startConfirm(paymentKeyCipher.encrypt(request.getPaymentKey()), firstLookupAt);

        return ChargeConfirmResponse.from(charge);
    }

    // TX2: 토스 승인 성공(사용자 승인 요청 또는 복구 스케줄러 조회) → APPROVED + 적립 + 거래 내역 + 미수금 차감.
    // 잠금 순서는 지갑 → 충전 → REVENUE(미수금이 있을 때만). REVENUE를 잡은 뒤 충전 행을 잡는 경로는 없다
    @Transactional
    public ChargeConfirmResponse completeConfirm(Long chargeId, LocalDateTime approvedAt) {
        Long memberId = chargeRepository.findMemberIdById(chargeId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.CHARGE_NOT_FOUND));

        Wallet wallet = walletRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.WALLET_NOT_FOUND));
        Charge charge = chargeRepository.findByIdAndMemberIdForUpdate(chargeId, memberId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.CHARGE_NOT_FOUND));

        // 이미 처리된 충전이면 여기서 CHARGE_ALREADY_PROCESSED → 입금과 차감까지 가지 않는다
        charge.completeConfirm(approvedAt);
        memberWalletDepositor.deposit(wallet, charge.getAmount(), w -> WalletTransaction.chargeIn(w, charge));

        return ChargeConfirmResponse.from(charge);
    }

    // TX2: 토스 거절 또는 조회 결과 ABORTED, EXPIRED → FAILED
    @Transactional
    public void fail(Long chargeId) {
        Long memberId = chargeRepository.findMemberIdById(chargeId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.CHARGE_NOT_FOUND));
        Charge charge = chargeRepository.findByIdAndMemberIdForUpdate(chargeId, memberId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.CHARGE_NOT_FOUND));

        charge.fail();
    }

    // 복구 스케줄러: 처리할 충전 한 건을 SKIP LOCKED로 잡고 임대(next_attempt_at = 지금 + lease)를 건 뒤 커밋한다.
    // 토스 조회는 이 트랜잭션 밖에서 한다. 처리 중 인스턴스가 죽어도 임대가 끝나면 다시 대상이 된다
    @Transactional
    public Optional<ChargeRecoveryTarget> claimNextRecoveryTarget() {
        LocalDateTime now = LocalDateTime.now(clock);
        // DB 칸(timestamp(6))과 같은 정밀도로 맞춰 반영할 때 임대 값 비교가 어긋나지 않게
        LocalDateTime leasedUntil = now.plus(recoveryProperties.lease()).truncatedTo(ChronoUnit.MICROS);

        return chargeRepository.lockNextRecoveryTarget(now)
                .flatMap(id -> {
                    chargeRepository.leaseRecovery(id, leasedUntil, now);
                    return chargeRepository.findRecoveryTarget(id);
                });
    }

    // 복구 스케줄러: 결과를 아직 확정하지 못해 다음 조회로 미룬다. 지갑을 잠그지 않는 조건부 update 한 문장
    // 확보할 때 읽은 attempt_count, 임대 값과 다르거나 PENDING이 아니면 0건 → false
    @Transactional
    public boolean deferRecovery(ChargeRecoveryTarget target, int attemptCount, LocalDateTime nextAttemptAt) {
        return chargeRepository.deferRecovery(target.id(), target.attemptCount(), target.leasedUntil(),
                attemptCount, nextAttemptAt, LocalDateTime.now(clock)) == 1;
    }

    // 복구 스케줄러: 자동 복구를 포기하고 REVIEW_REQUIRED로. 조건은 deferRecovery와 같다
    @Transactional
    public boolean markReviewRequired(ChargeRecoveryTarget target, int attemptCount) {
        return chargeRepository.markReviewRequired(target.id(), target.attemptCount(), target.leasedUntil(),
                attemptCount, LocalDateTime.now(clock)) == 1;
    }
}
