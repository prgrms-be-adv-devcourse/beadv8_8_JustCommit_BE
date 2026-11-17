package com.justcommit.backend.settlement.application.payout;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

// 정산 지급 스케줄러 설정 (#429). 값의 원본은 docs/reference/schedulers.md 「정산 지급 큐」
@ConfigurationProperties("custom.settlement.payout")
public record SettlementPayoutProperties(
        // 스케줄러 실행 간격 (fixedDelay)
        @DefaultValue("10s") Duration interval,
        // 한 번 실행할 때 처리하는 최대 건수
        @DefaultValue("50") int batchSize,
        // 한 건을 확보한 뒤 다른 실행이 다시 잡지 않게 next_attempt_at을 미루는 시간.
        // payment 호출 최악 시간(연결 1초 + 0.2초 쉬고 연결 재시도 1초 + 응답 3초)과 결과 반영보다 넉넉하게
        @DefaultValue("30s") Duration lease,
        // 지급 실패 뒤 다음 시도까지. n번째 실패 뒤에는 n번째 값, 그 뒤로는 마지막 값 (포기 없음)
        @DefaultValue({"10s", "30s", "1m"}) List<Duration> retryDelays,
        // 정산 생성 뒤 이 시간이 지나도 미지급(PENDING, FAILED)이면 지연으로 본다
        @DefaultValue("10m") Duration delayThreshold,
        // 지연이 계속되면 이 간격마다 다시 ERROR 로그
        @DefaultValue("1h") Duration delayAlertRepeat
) {

    public SettlementPayoutProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("settlement.payout.batch-size는 1 이상이어야 합니다.");
        }
        requirePositive(lease, "lease");
        requirePositive(delayThreshold, "delay-threshold");
        requirePositive(delayAlertRepeat, "delay-alert-repeat");
        if (retryDelays == null || retryDelays.isEmpty()) {
            throw new IllegalArgumentException("settlement.payout.retry-delays는 1개 이상이어야 합니다.");
        }
        retryDelays.forEach(delay -> requirePositive(delay, "retry-delays"));
        retryDelays = List.copyOf(retryDelays);
    }

    // 지금까지 실패 횟수(이번 실패를 더하기 전 attemptCount) 뒤의 다음 시도 간격: 0 → 10초, 1 → 30초, 2 이상 → 1분
    public Duration retryDelayAfter(int previousFailures) {
        return retryDelays.get(Math.min(Math.max(previousFailures, 0), retryDelays.size() - 1));
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("settlement.payout." + name + "는 0보다 커야 합니다.");
        }
    }
}
