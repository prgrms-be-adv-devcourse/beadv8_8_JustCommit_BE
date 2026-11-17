package com.justcommit.backend.settlement.application.payout;

import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
import com.justcommit.backend.common.client.exception.InternalApiUnavailableException;
import com.justcommit.backend.settlement.client.payment.SettlementPayoutClient;
import com.justcommit.backend.settlement.domain.Settlement;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

// 정산 지급 (#429, docs/reference/schedulers.md 「정산 지급 큐」)
//
// 한 건씩:
// 1. 확보 Tx: FOR UPDATE SKIP LOCKED로 지급 대상(PENDING · FAILED, next_attempt_at <= now) 한 건을 잡고
//    next_attempt_at을 임대 시간만큼 미룬 뒤 바로 커밋. 다른 파드 · 다음 회차가 같은 건을 바로 집지 않는다
// 2. 호출: 트랜잭션 밖에서 payment 지급 (Idempotency-Key = settlementId). 정산 행 잠금 · DB 트랜잭션을 잡지 않는다
// 3. 결과 Tx: 행을 잠그고 성공이면 complete, 실패면 임대가 그대로일 때만 fail + 다음 시도 시각
//
// - 실패 종류(연결 실패, 결과 불명, 4xx, 5xx, 그 밖의 예외)는 모두 같은 방식으로 다시 시도한다 (포기 없음).
//   payment가 settlementId 멱등이라 결과를 모르는 건을 같은 키로 다시 보내도 지급은 한 번이다
// - 성공은 임대를 검사하지 않는다. 지급 성공은 누가 반영해도 결과가 같고, COMPLETED면 complete가 아무것도 하지 않는다
// - 실패는 임대가 그대로일 때만 반영한다. 임대가 끝나 다른 실행이 다시 잡은 건을 늦게 온 실패가 덮어쓰지 않게
// - 시각은 건마다 Clock에서 읽고 마이크로초로 자른다 (timestamp(6)에 저장된 임대 값과 그대로 비교되게)
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementPayoutService {

    private final TransactionTemplate transactionTemplate;
    private final SettlementRepository settlementRepository;
    private final SettlementPayoutClient settlementPayoutClient;
    private final SettlementPayoutProperties properties;
    private final Clock clock;

    // @return 이번 실행에 확보해 처리한 건수
    public int payoutDue() {
        int processed = 0;
        while (processed < properties.batchSize()) {
            Optional<PayoutTarget> claimed = claimNext();
            if (claimed.isEmpty()) {
                break;
            }
            PayoutTarget target = claimed.get();
            processed++;
            try {
                payout(target);
            } catch (RuntimeException e) {
                // 결과 반영이 실패해도 배치를 멈추지 않는다. 임대가 끝나면(next_attempt_at = leasedUntil) 다시 잡힌다
                log.error("정산 지급 결과 반영 실패: settlementId={}", target.settlementId(), e);
            }
        }
        return processed;
    }

    // 한 건 잡아 임대하고 커밋
    Optional<PayoutTarget> claimNext() {
        LocalDateTime now = now();
        LocalDateTime leasedUntil = now.plus(properties.lease());
        return transactionTemplate.execute(status -> settlementRepository.findNextPayoutTargetForUpdate(now)
                .map(settlement -> {
                    settlement.lease(leasedUntil);
                    return PayoutTarget.of(settlement, leasedUntil);
                }));
    }

    private void payout(PayoutTarget target) {
        try {
            settlementPayoutClient.distributeSettlement(
                    target.settlementId(), target.sellerId(), target.payoutAmount(), target.fee());
        } catch (RuntimeException e) {
            recordFailure(target, e);
            return;
        }
        recordSuccess(target);
    }

    private void recordSuccess(PayoutTarget target) {
        transactionTemplate.executeWithoutResult(status -> settlementRepository
                .findByIdForUpdate(target.settlementId())
                .ifPresent(settlement -> {
                    if (settlement.isCompleted()) {
                        log.info("이미 지급 완료된 정산이라 건너뜀: settlementId={}", target.settlementId());
                        return;
                    }
                    settlement.complete(now());
                    log.info("정산 지급 완료: settlementId={}, sellerId={}",
                            target.settlementId(), target.sellerId());
                }));
    }

    private void recordFailure(PayoutTarget target, RuntimeException e) {
        String reason = reason(e);
        transactionTemplate.executeWithoutResult(status -> settlementRepository
                .findByIdForUpdate(target.settlementId())
                .ifPresent(settlement -> {
                    if (!settlement.isLeasedUntil(target.leasedUntil())) {
                        log.info("다른 실행이 처리 중이거나 완료한 정산이라 실패를 반영하지 않음: settlementId={}",
                                target.settlementId());
                        return;
                    }
                    LocalDateTime nextAttemptAt = now().plus(properties.retryDelayAfter(settlement.getAttemptCount()));
                    settlement.fail(reason, nextAttemptAt);
                    log.warn("정산 지급 실패, {}번째 실패라 다시 시도함: settlementId={}, 다음 시도={}, 사유={}",
                            settlement.getAttemptCount(), target.settlementId(), nextAttemptAt, reason);
                }));
    }

    // last_error에 남길 사유. 결과 불명 처리 규칙(docs/conventions/resilience.md)의 원인 구분
    static String reason(RuntimeException e) {
        String detail = e.getMessage();
        return switch (e) {
            case InternalApiErrorException error -> "HTTP_" + error.status()
                    + (error.code() != null ? " " + error.code() : "") + ": " + detail;
            case InternalApiResultUnknownException ignored -> "RESULT_UNKNOWN: " + detail;
            case InternalApiUnavailableException ignored -> "CONNECTION_FAILED: " + detail;
            default -> e.getClass().getSimpleName() + ": " + detail;
        };
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
