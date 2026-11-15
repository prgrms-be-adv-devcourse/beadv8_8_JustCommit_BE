package com.justcommit.backend.payment.application.recovery;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.application.ChargeService;
import com.justcommit.backend.payment.application.recovery.ChargeRecoveryDecision.LookupResult;
import com.justcommit.backend.payment.application.recovery.ChargeRecoveryDecision.Outcome;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.pg.TossLookupFailedException;
import com.justcommit.backend.payment.infrastructure.pg.TossLookupUnauthorizedException;
import com.justcommit.backend.payment.infrastructure.pg.TossPaymentClient;
import com.justcommit.backend.payment.infrastructure.pg.TossPaymentNotFoundException;
import com.justcommit.backend.payment.infrastructure.repository.ChargeRecoveryTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

// 결과 불명 충전 복구: 다음 조회 시각이 지난 PENDING 충전을 한 건씩 확보(SKIP LOCKED + 임대) → 토스 조회(트랜잭션 밖) → 판정 → 반영
//
// - 적립과 FAILED는 사용자 승인 경로와 같은 ChargeService.completeConfirm, fail을 쓴다 (잠금 순서 지갑 → 충전)
// - 보류와 REVIEW_REQUIRED는 지갑을 잠그지 않는 조건부 update. 다른 경로가 먼저 바꿨거나 임대가 끝나
//   다른 인스턴스가 다시 잡았으면 0건으로 무시 (횟수와 다음 조회 시각은 시도마다 달라 섞이면 안 됨)
// - 승인과 실패는 임대를 검사하지 않는다. 토스 확정 결과(DONE, ABORTED, EXPIRED)라 누가 반영해도 결과가 같고,
//   충전 행 잠금과 PENDING 확인으로 한 번만 반영된다 (사용자 승인 경로와 같은 메서드)
// - 시각은 건마다 Clock에서 읽는다 (한 번 실행이 길어져도 임대와 판정 시각이 늦지 않게)
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargeRecoveryService {

    private final ChargeService chargeService;
    private final TossPaymentClient tossPaymentClient;
    private final ChargeRecoveryDecision decision;
    private final ChargeRecoveryProperties properties;
    private final Clock clock;

    // @return 이번 실행에 확보해 처리한 건수
    public int recoverDue() {
        int processed = 0;
        while (processed < properties.batchSize()) {
            Optional<ChargeRecoveryTarget> claimed = chargeService.claimNextRecoveryTarget();
            if (claimed.isEmpty()) {
                break;
            }
            ChargeRecoveryTarget target = claimed.get();
            processed++;
            try {
                recover(target);
            } catch (RuntimeException e) {
                // 한 건 실패가 배치를 멈추지 않게. 임대가 끝나면(next_attempt_at = leasedUntil) 다시 잡힌다
                log.error("충전 복구 처리 실패: chargeId={}, orderId={}", target.id(), target.pgOrderNo(), e);
            }
        }
        return processed;
    }

    private void recover(ChargeRecoveryTarget target) {
        LookupResult result = lookup(target.pgOrderNo());
        Outcome outcome = decision.decide(result, target, LocalDateTime.now(clock));

        switch (outcome) {
            case Outcome.Approve approve -> approve(target, approve);
            case Outcome.Fail fail -> fail(target, fail);
            case Outcome.RetryLater retry -> retryLater(target, retry);
            case Outcome.ReviewRequired review -> reviewRequired(target, review);
        }
    }

    private LookupResult lookup(String orderId) {
        try {
            return new LookupResult.Found(tossPaymentClient.getPaymentByOrderId(orderId));
        } catch (TossPaymentNotFoundException e) {
            return new LookupResult.NotFound();
        } catch (TossLookupUnauthorizedException e) {
            return new LookupResult.Unauthorized();
        } catch (TossLookupFailedException e) {
            return new LookupResult.Failed();
        }
    }

    private void approve(ChargeRecoveryTarget target, Outcome.Approve approve) {
        try {
            chargeService.completeConfirm(target.id(), approve.approvedAt());
            log.info("결과 불명 충전 승인 반영: chargeId={}, orderId={}", target.id(), target.pgOrderNo());
        } catch (BusinessException e) {
            skipIfAlreadyProcessed(target, e);
        }
    }

    private void fail(ChargeRecoveryTarget target, Outcome.Fail fail) {
        try {
            chargeService.fail(target.id());
            log.info("결과 불명 충전 실패 확정: chargeId={}, orderId={}, 토스 상태={}",
                    target.id(), target.pgOrderNo(), fail.tossStatus());
        } catch (BusinessException e) {
            skipIfAlreadyProcessed(target, e);
        }
    }

    private void retryLater(ChargeRecoveryTarget target, Outcome.RetryLater retry) {
        if (retry.authError()) {
            log.error("토스 결제 조회 권한 오류(401, 403): 시크릿 키 설정 확인 필요. chargeId={}, orderId={}, 다음 조회={}",
                    target.id(), target.pgOrderNo(), retry.nextAttemptAt());
        }
        boolean updated = chargeService.deferRecovery(target, retry.attemptCount(), retry.nextAttemptAt());
        if (!updated) {
            logChangedElsewhere(target);
        }
    }

    private void reviewRequired(ChargeRecoveryTarget target, Outcome.ReviewRequired review) {
        boolean updated = chargeService.markReviewRequired(target, review.attemptCount());
        if (!updated) {
            logChangedElsewhere(target);
            return;
        }
        log.error("충전 확인 필요(REVIEW_REQUIRED): chargeId={}, orderId={}, 사유={}",
                target.id(), target.pgOrderNo(), review.reason());
    }

    // 사용자 승인 등 다른 경로가 먼저 확정한 건은 건너뛴다
    private void skipIfAlreadyProcessed(ChargeRecoveryTarget target, BusinessException e) {
        if (e.getErrorCode() != PaymentErrorCode.CHARGE_ALREADY_PROCESSED) {
            throw e;
        }
        logChangedElsewhere(target);
    }

    private void logChangedElsewhere(ChargeRecoveryTarget target) {
        log.info("다른 경로가 먼저 처리한 충전이라 건너뜀: chargeId={}, orderId={}", target.id(), target.pgOrderNo());
    }
}
