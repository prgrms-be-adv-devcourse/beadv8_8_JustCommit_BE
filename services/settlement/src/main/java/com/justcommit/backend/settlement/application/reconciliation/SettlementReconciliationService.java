package com.justcommit.backend.settlement.application.reconciliation;

import com.justcommit.backend.settlement.client.payment.SettlementLedgerClient;
import com.justcommit.backend.settlement.client.payment.SettlementLedgerEntry;
import com.justcommit.backend.settlement.domain.reconciliation.ReconciliationRunResult;
import com.justcommit.backend.settlement.infrastructure.metrics.SettlementReconciliationMetrics;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementReconciliationRunRepository;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// 정산 대사 (docs/flows/confirm-settlement.md 「정산 대사」).
// settlement의 정산과 payment의 정산 원장을 비교해 불일치를 기록한다. 데이터를 고치지 않고 찾기만 한다.
//   1. 짧은 읽기 전용 트랜잭션으로 대상 정산을 읽는다
//   2. 배치(기본 100개)마다 트랜잭션 밖에서 payment 정산 원장을 조회한다. 실패하거나 응답 키가 요청과 다르면 그 배치만 건너뜀
//   3. 판정기로 비교하고, 배치마다 짧은 트랜잭션으로 불일치를 기록한다
//   4. 실행 결과를 settlement_reconciliation_run에 남기고 지표를 올린다
// 예외를 밖으로 던지지 않고 결과(FAILED)로 돌려준다
@Slf4j
@Service
public class SettlementReconciliationService {

    private final SettlementRepository settlementRepository;
    private final SettlementLedgerClient ledgerClient;
    private final ReconciliationMismatchRecorder recorder;
    private final SettlementReconciliationRunRepository runRepository;
    private final SettlementReconciliationMetrics metrics;
    private final SettlementReconciliationProperties properties;
    private final Clock clock;
    private final TransactionTemplate readOnlyTransaction;

    public SettlementReconciliationService(SettlementRepository settlementRepository,
                                           SettlementLedgerClient ledgerClient,
                                           ReconciliationMismatchRecorder recorder,
                                           SettlementReconciliationRunRepository runRepository,
                                           SettlementReconciliationMetrics metrics,
                                           SettlementReconciliationProperties properties,
                                           Clock clock,
                                           PlatformTransactionManager transactionManager) {
        this.settlementRepository = settlementRepository;
        this.ledgerClient = ledgerClient;
        this.recorder = recorder;
        this.runRepository = runRepository;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    public ReconciliationRunResult reconcile() {
        long startNanos = System.nanoTime();
        LocalDateTime startedAt = LocalDateTime.now(clock);
        Counts counts = new Counts();
        ReconciliationRunResult result;
        try {
            List<ReconciliationTarget> targets = readOnlyTransaction.execute(status ->
                    settlementRepository.findReconciliationTargets(startedAt.minus(properties.lookback())));
            List<ReconciliationTarget> loaded = targets == null ? List.of() : targets;
            counts.targets = loaded.size();
            for (int from = 0; from < loaded.size(); from += properties.batchSize()) {
                reconcileBatch(loaded.subList(from, Math.min(from + properties.batchSize(), loaded.size())), counts);
            }
            result = counts.failedBatches == 0 ? ReconciliationRunResult.SUCCEEDED : ReconciliationRunResult.PARTIAL;
        } catch (RuntimeException e) {
            result = ReconciliationRunResult.FAILED;
            log.atError()
                    .setCause(e)
                    .addKeyValue("targetCount", counts.targets)
                    .log("정산 대사 실행이 실패했습니다.");
        }

        LocalDateTime finishedAt = LocalDateTime.now(clock);
        saveRun(startedAt, finishedAt, result, counts);
        metrics.runFinished(result, Duration.ofNanos(System.nanoTime() - startNanos));
        log.atInfo()
                .addKeyValue("result", result)
                .addKeyValue("targetCount", counts.targets)
                .addKeyValue("mismatchCount", counts.mismatches)
                .addKeyValue("newMismatchCount", counts.inserted)
                .addKeyValue("alreadyRecordedCount", counts.alreadyRecorded)
                .addKeyValue("recheckSkippedCount", counts.recheckSkipped)
                .addKeyValue("failedBatchCount", counts.failedBatches)
                .log("정산 대사를 마쳤습니다.");
        return result;
    }

    private void reconcileBatch(List<ReconciliationTarget> batch, Counts counts) {
        List<Long> ids = batch.stream().map(ReconciliationTarget::settlementId).toList();
        Map<Long, List<SettlementLedgerEntry>> ledgers = fetchLedgers(ids);
        if (ledgers == null) {
            counts.failedBatches++;
            metrics.batchFailed();
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        List<DetectedMismatch> found = new ArrayList<>();
        for (ReconciliationTarget target : batch) {
            found.addAll(SettlementLedgerReconciler.reconcile(
                    target, ledgers.get(target.settlementId()), now, properties.unreflectedGrace()));
        }
        counts.mismatches += found.size();

        ReconciliationMismatchRecorder.RecordOutcome outcome = recorder.record(found, now);
        counts.inserted += outcome.inserted();
        counts.alreadyRecorded += outcome.alreadyRecorded();
        counts.recheckSkipped += outcome.recheckSkipped();
    }

    // 실패하면 null. 응답 키가 요청한 ID와 정확히 같을 때만 비교한다 (빠진 ID는 원장 없음으로, 섞인 ID는 엉뚱한 비교로 오탐이 나므로)
    private Map<Long, List<SettlementLedgerEntry>> fetchLedgers(List<Long> ids) {
        try {
            Map<Long, List<SettlementLedgerEntry>> ledgers = ledgerClient.findLedgers(ids);
            if (ledgers == null || !ledgers.keySet().equals(new HashSet<>(ids))
                    || ledgers.values().stream().anyMatch(Objects::isNull)) {
                log.atWarn()
                        .addKeyValue("requestedCount", ids.size())
                        .addKeyValue("responseCount", ledgers == null ? 0 : ledgers.size())
                        .addKeyValue("firstSettlementId", ids.getFirst())
                        .log("정산 원장 응답이 요청과 맞지 않아 배치를 건너뜁니다.");
                return null;
            }
            return ledgers;
        } catch (RuntimeException e) {
            log.atWarn()
                    .setCause(e)
                    .addKeyValue("requestedCount", ids.size())
                    .addKeyValue("firstSettlementId", ids.getFirst())
                    .log("정산 원장 조회가 실패해 배치를 건너뜁니다.");
            return null;
        }
    }

    // 이력을 못 남기면 마지막 성공 시각이 오르지 않아 미실행 알림으로 드러난다
    private void saveRun(LocalDateTime startedAt, LocalDateTime finishedAt, ReconciliationRunResult result,
                         Counts counts) {
        try {
            runRepository.insert(startedAt, finishedAt, result, counts.targets, counts.mismatches,
                    counts.failedBatches);
        } catch (RuntimeException e) {
            log.atError()
                    .setCause(e)
                    .addKeyValue("result", result)
                    .log("정산 대사 실행 이력을 저장하지 못했습니다.");
        }
    }

    private static final class Counts {
        int targets;
        int mismatches;
        int inserted;
        int alreadyRecorded;
        int recheckSkipped;
        int failedBatches;
    }
}
