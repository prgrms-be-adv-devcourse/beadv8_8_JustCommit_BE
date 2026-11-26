package com.justcommit.backend.settlement.application.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// 정산 대사 설정. 값의 원본은 docs/reference/schedulers.md 「정산 대사」
// 실행 시각(cron)과 끄기(enabled)는 스케줄러 애너테이션이 같은 키를 직접 읽는다
@ConfigurationProperties("custom.settlement.reconciliation")
public record SettlementReconciliationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 40 9 * * *") String cron,
        // COMPLETED 정산은 completed_at이 이 기간 안인 것만 본다. PENDING, FAILED는 기간과 상관없이 모두 본다.
        // EC2가 꺼져 며칠 대사가 빠져도 그 사이 완료된 정산이 빠지지 않게 넉넉히 둔다
        @DefaultValue("7d") Duration lookback,
        // payment 지급 시각에서 이 시간이 지나도 정산이 PENDING, FAILED면 UNREFLECTED_STATUS.
        // 정산 지급 지연 기준(custom.settlement.payout.delay-threshold)과 같은 값
        @DefaultValue("10m") Duration unreflectedGrace,
        // payment 정산 원장 조회 한 번에 보내는 정산 수 (payment API 상한 100)
        @DefaultValue("100") int batchSize
) {

    static final int MAX_BATCH_SIZE = 100;

    public SettlementReconciliationProperties {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("settlement.reconciliation.batch-size는 1 이상 100 이하여야 합니다.");
        }
        requirePositive(lookback, "lookback");
        requirePositive(unreflectedGrace, "unreflected-grace");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("settlement.reconciliation." + name + "는 0보다 커야 합니다.");
        }
    }
}
