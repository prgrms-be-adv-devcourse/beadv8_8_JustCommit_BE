package com.justcommit.backend.settlement.infrastructure.metrics;

import com.justcommit.backend.settlement.domain.reconciliation.ReconciliationRunResult;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementReconciliationMismatchRepository;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementReconciliationRunRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleSupplier;

// 정산 대사 지표 (docs/infra/observability.md 「알림 규칙」)
//   settlement_reconciliation_mismatches                    미해결 대사 불일치 수 (수집할 때마다 DB에서 셈)
//   settlement_reconciliation_last_success_timestamp_seconds 마지막 SUCCEEDED 실행이 끝난 시각, 성공 이력이 없으면
//                                                            배포 시각(BASELINE 행) (수집할 때마다 DB에서 읽음)
//   settlement_reconciliation_runs_total{result}             실행 결과별 횟수 (succeeded, partial, failed. BASELINE은 실행이 아니라 없음)
//   settlement_reconciliation_failed_batches_total           payment 조회 실패로 건너뛴 배치 수
//   settlement_reconciliation_duration_seconds               실행 시간
// 두 Gauge는 메모리가 아니라 DB에서 읽는다. EC2를 매일 새로 켜도 값이 0으로 돌아가 알림이 거짓으로 울리지 않게
@Slf4j
@Component
public class SettlementReconciliationMetrics {

    static final String MISMATCHES = "settlement.reconciliation.mismatches";
    static final String LAST_SUCCESS = "settlement.reconciliation.last.success.timestamp";
    static final String RUNS = "settlement.reconciliation.runs";
    static final String FAILED_BATCHES = "settlement.reconciliation.failed.batches";
    static final String DURATION = "settlement.reconciliation.duration";

    private final SettlementReconciliationMismatchRepository mismatchRepository;
    private final SettlementReconciliationRunRepository runRepository;
    private final Clock clock;
    private final Map<ReconciliationRunResult, Counter> runs = new EnumMap<>(ReconciliationRunResult.class);
    private final Counter failedBatches;
    private final Timer duration;

    public SettlementReconciliationMetrics(SettlementReconciliationMismatchRepository mismatchRepository,
                                           SettlementReconciliationRunRepository runRepository,
                                           Clock clock, MeterRegistry registry) {
        this.mismatchRepository = mismatchRepository;
        this.runRepository = runRepository;
        this.clock = clock;
        Gauge.builder(MISMATCHES, this, SettlementReconciliationMetrics::unresolvedMismatches)
                .description("해결하지 않은 정산 대사 불일치 수")
                .register(registry);
        Gauge.builder(LAST_SUCCESS, this, SettlementReconciliationMetrics::lastSuccessEpochSeconds)
                .description("마지막으로 성공(SUCCEEDED)한 정산 대사가 끝난 시각")
                .baseUnit("seconds")
                .register(registry);
        // 결과마다 미리 등록해 두어 첫 실패도 increase()로 잡힌다. BASELINE은 실행 결과가 아니라 등록하지 않는다
        for (ReconciliationRunResult result : ReconciliationRunResult.values()) {
            if (!result.isRunResult()) {
                continue;
            }
            runs.put(result, Counter.builder(RUNS)
                    .description("정산 대사 실행 결과별 횟수")
                    .tag("result", result.name().toLowerCase(Locale.ROOT))
                    .register(registry));
        }
        this.failedBatches = Counter.builder(FAILED_BATCHES)
                .description("payment 정산 원장 조회가 실패해 건너뛴 배치 수")
                .register(registry);
        this.duration = Timer.builder(DURATION)
                .description("정산 대사 실행 시간")
                .register(registry);
    }

    public void batchFailed() {
        failedBatches.increment();
    }

    public void runFinished(ReconciliationRunResult result, Duration elapsed) {
        if (!result.isRunResult()) {
            throw new IllegalArgumentException("BASELINE은 실행 결과가 아닙니다.");
        }
        runs.get(result).increment();
        duration.record(elapsed);
    }

    double unresolvedMismatches() {
        return read("미해결 대사 불일치 수", mismatchRepository::countUnresolved);
    }

    // 성공 이력이 없으면 배포 시각(BASELINE)을 내보내, 배포 뒤 25시간 안에 한 번도 성공하지 못하면 미실행 알림이 울린다.
    // 기준 행도 없거나 DB를 못 읽으면 NaN (값 없음). 0이면 미실행 알림이 바로 울리므로 쓰지 않는다
    double lastSuccessEpochSeconds() {
        return read("마지막 정산 대사 성공 시각", () -> runRepository.findLastSuccessOrBaselineAt()
                .map(finishedAt -> (double) finishedAt.atZone(clock.getZone()).toEpochSecond())
                .orElse(Double.NaN));
    }

    // DB를 못 읽으면 0이 아니라 NaN으로 둬서 알림이 거짓으로 꺼지지 않게 한다
    private double read(String what, DoubleSupplier query) {
        try {
            return query.getAsDouble();
        } catch (RuntimeException e) {
            log.warn("{}를 읽지 못함: {}", what, e.getMessage());
            return Double.NaN;
        }
    }
}
