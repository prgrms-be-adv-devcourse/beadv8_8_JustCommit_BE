package com.justcommit.backend.settlement.application.payout;

import com.justcommit.backend.settlement.domain.SettlementStatus;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

// 정산 지급 지연 감시 (#429, docs/infra/observability.md 「정산 지급 지연」)
// 건마다가 아니라 "delayThreshold 넘게 미지급(PENDING, FAILED) 정산이 1건 이상 있다"는 상태 기준으로 ERROR 1줄.
// 지연이 계속되면 delayAlertRepeat마다 다시, 모두 지급되면 INFO 1줄. 상태는 이 인스턴스 메모리에만 둔다
// (스케줄러 스레드 하나에서만 부른다)
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementPayoutDelayMonitor {

    static final Set<SettlementStatus> UNFINISHED = EnumSet.of(SettlementStatus.PENDING, SettlementStatus.FAILED);

    public enum Result { NONE, ALERTED, STILL_DELAYED, RECOVERED }

    private final SettlementRepository settlementRepository;
    private final SettlementPayoutProperties properties;
    private final Clock clock;

    // 마지막으로 ERROR를 남긴 시각. 지연 상태가 아니면 null
    private LocalDateTime lastAlertAt;

    public Result check() {
        LocalDateTime now = LocalDateTime.now(clock);
        long delayed = settlementRepository.countByStatusInAndCreatedAtBefore(
                UNFINISHED, now.minus(properties.delayThreshold()));

        if (delayed == 0) {
            if (lastAlertAt == null) {
                return Result.NONE;
            }
            lastAlertAt = null;
            log.info("지급 지연 정산이 모두 해소됨");
            return Result.RECOVERED;
        }

        if (lastAlertAt != null && now.isBefore(lastAlertAt.plus(properties.delayAlertRepeat()))) {
            return Result.STILL_DELAYED;
        }
        lastAlertAt = now;
        settlementRepository.findFirstByStatusInOrderByCreatedAtAsc(UNFINISHED).ifPresentOrElse(
                oldest -> log.error("{} 넘게 미지급 정산이 있음: 건수={}, 가장 오래된 settlementId={}, 생성={}, 상태={}, "
                                + "시도 횟수={}, 마지막 실패={}",
                        properties.delayThreshold(), delayed, oldest.getId(), oldest.getCreatedAt(),
                        oldest.getStatus(), oldest.getAttemptCount(), oldest.getLastError()),
                () -> log.error("{} 넘게 미지급 정산이 있음: 건수={}", properties.delayThreshold(), delayed));
        return Result.ALERTED;
    }
}
