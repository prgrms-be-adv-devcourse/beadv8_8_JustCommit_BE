package com.justcommit.backend.settlement.application.reconciliation;

import com.justcommit.backend.settlement.client.payment.SettlementLedgerClient;
import com.justcommit.backend.settlement.client.payment.SettlementLedgerEntry;
import com.justcommit.backend.settlement.domain.SettlementStatus;
import com.justcommit.backend.settlement.domain.reconciliation.MismatchType;
import com.justcommit.backend.settlement.domain.reconciliation.ReconciliationRunResult;
import com.justcommit.backend.settlement.infrastructure.metrics.SettlementReconciliationMetrics;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementReconciliationMismatchRepository;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementReconciliationRunRepository;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 정산 대사 실행 흐름: 배치 나누기, 실패한 배치 건너뛰기, 실행 결과(SUCCEEDED, PARTIAL, FAILED) 기록과 지표
// 결과 판정: 실패한 배치 없음(대상 0건 포함) → SUCCEEDED, 일부 실패 → PARTIAL, 모두 실패 또는 예외 → FAILED
class SettlementReconciliationServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 9, 40);
    private static final long SELLER_ID = 7L;

    private final SettlementRepository settlementRepository = mock(SettlementRepository.class);
    private final SettlementLedgerClient ledgerClient = mock(SettlementLedgerClient.class);
    private final ReconciliationMismatchRecorder recorder = mock(ReconciliationMismatchRecorder.class);
    private final SettlementReconciliationRunRepository runRepository = mock(SettlementReconciliationRunRepository.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        when(recorder.record(anyList(), any())).thenReturn(ReconciliationMismatchRecorder.RecordOutcome.NONE);
    }

    @Test
    @DisplayName("최근 7일 COMPLETED와 모든 PENDING, FAILED를 대상으로 읽고, 모두 맞으면 SUCCEEDED")
    void succeeded() {
        givenTargets(target(1L), target(2L));
        when(ledgerClient.findLedgers(anyCollection())).thenReturn(Map.of(1L, paidLedger(), 2L, paidLedger()));

        ReconciliationRunResult result = service(100).reconcile();

        assertThat(result).isEqualTo(ReconciliationRunResult.SUCCEEDED);
        verify(settlementRepository).findReconciliationTargets(NOW.minusDays(7));
        verify(recorder).record(List.of(), NOW);
        verify(runRepository).insert(NOW, NOW, ReconciliationRunResult.SUCCEEDED, 2, 0, 0);
        assertThat(runs("succeeded")).isEqualTo(1);
        assertThat(registry.get("settlement.reconciliation.duration").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("배치 크기만큼 나눠 payment를 부른다")
    void splitsIntoBatches() {
        givenTargets(target(1L), target(2L), target(3L));
        when(ledgerClient.findLedgers(anyCollection())).thenAnswer(invocation -> ledgersFor(invocation.getArgument(0)));

        service(2).reconcile();

        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.captor();
        verify(ledgerClient, times(2)).findLedgers(ids.capture());
        assertThat(ids.getAllValues()).containsExactly(List.of(1L, 2L), List.of(3L));
    }

    @Test
    @DisplayName("한 배치의 payment 조회가 실패하면 그 배치만 건너뛰고 PARTIAL")
    void skipsFailedBatch() {
        givenTargets(target(1L), target(2L));
        when(ledgerClient.findLedgers(List.of(1L))).thenThrow(new IllegalStateException("payment 응답 없음"));
        when(ledgerClient.findLedgers(List.of(2L))).thenReturn(Map.of(2L, paidLedger()));

        ReconciliationRunResult result = service(1).reconcile();

        assertThat(result).isEqualTo(ReconciliationRunResult.PARTIAL);
        verify(recorder, times(1)).record(anyList(), any());
        verify(runRepository).insert(NOW, NOW, ReconciliationRunResult.PARTIAL, 2, 0, 1);
        assertThat(registry.get("settlement.reconciliation.failed.batches").counter().count()).isEqualTo(1);
        assertThat(runs("partial")).isEqualTo(1);
    }

    @Test
    @DisplayName("응답에 요청한 ID가 빠지면 그 배치를 비교하지 않는다 (배치가 하나뿐이라 FAILED)")
    void skipsBatchWithMissingKey() {
        givenTargets(target(1L), target(2L));
        when(ledgerClient.findLedgers(anyCollection())).thenReturn(Map.of(1L, paidLedger()));

        assertThat(service(100).reconcile()).isEqualTo(ReconciliationRunResult.FAILED);
        verify(recorder, never()).record(anyList(), any());
        verify(runRepository).insert(NOW, NOW, ReconciliationRunResult.FAILED, 2, 0, 1);
    }

    @Test
    @DisplayName("응답에 요청하지 않은 ID가 섞여도 그 배치를 비교하지 않는다 (배치가 하나뿐이라 FAILED)")
    void skipsBatchWithExtraKey() {
        givenTargets(target(1L));
        when(ledgerClient.findLedgers(anyCollection())).thenReturn(Map.of(1L, paidLedger(), 99L, List.of()));

        assertThat(service(100).reconcile()).isEqualTo(ReconciliationRunResult.FAILED);
        verify(recorder, never()).record(anyList(), any());
    }

    @Test
    @DisplayName("모든 배치의 payment 조회가 실패하면 아무것도 비교하지 못했으므로 FAILED")
    void allBatchesFailed() {
        givenTargets(target(1L), target(2L));
        when(ledgerClient.findLedgers(anyCollection())).thenThrow(new IllegalStateException("payment 응답 없음"));

        ReconciliationRunResult result = service(1).reconcile();

        assertThat(result).isEqualTo(ReconciliationRunResult.FAILED);
        verify(recorder, never()).record(anyList(), any());
        verify(runRepository).insert(NOW, NOW, ReconciliationRunResult.FAILED, 2, 0, 2);
        assertThat(registry.get("settlement.reconciliation.failed.batches").counter().count()).isEqualTo(2);
        assertThat(runs("failed")).isEqualTo(1);
        assertThat(runs("partial")).isZero();
    }

    @Test
    @DisplayName("대상이 없으면 payment를 부르지 않고 SUCCEEDED")
    void noTargets() {
        givenTargets();

        assertThat(service(100).reconcile()).isEqualTo(ReconciliationRunResult.SUCCEEDED);

        verify(ledgerClient, never()).findLedgers(anyCollection());
        verify(runRepository).insert(NOW, NOW, ReconciliationRunResult.SUCCEEDED, 0, 0, 0);
    }

    @Test
    @DisplayName("결과 판정: 실패 0개 SUCCEEDED, 일부 PARTIAL, 전부 FAILED")
    void resultOf() {
        assertThat(SettlementReconciliationService.resultOf(0, 0)).isEqualTo(ReconciliationRunResult.SUCCEEDED);
        assertThat(SettlementReconciliationService.resultOf(3, 0)).isEqualTo(ReconciliationRunResult.SUCCEEDED);
        assertThat(SettlementReconciliationService.resultOf(3, 1)).isEqualTo(ReconciliationRunResult.PARTIAL);
        assertThat(SettlementReconciliationService.resultOf(3, 2)).isEqualTo(ReconciliationRunResult.PARTIAL);
        assertThat(SettlementReconciliationService.resultOf(3, 3)).isEqualTo(ReconciliationRunResult.FAILED);
        assertThat(SettlementReconciliationService.resultOf(1, 1)).isEqualTo(ReconciliationRunResult.FAILED);
    }

    @Test
    @DisplayName("BASELINE은 실행 결과가 아니라 실행 횟수 지표에 없다")
    void baselineNotInRunCounter() {
        service(100);

        assertThat(registry.find("settlement.reconciliation.runs").tag("result", "baseline").counter()).isNull();
        assertThat(registry.find("settlement.reconciliation.runs").counters()).hasSize(3);
    }

    @Test
    @DisplayName("찾은 불일치를 배치마다 기록기로 넘긴다")
    void recordsMismatches() {
        givenTargets(target(1L));
        when(ledgerClient.findLedgers(anyCollection())).thenReturn(Map.of(1L, List.of()));

        service(100).reconcile();

        verify(recorder).record(List.of(new DetectedMismatch(1L, MismatchType.MISSING_LEDGER,
                "판매자 SETTLEMENT IN 1건", "원장 없음")), NOW);
        verify(runRepository).insert(NOW, NOW, ReconciliationRunResult.SUCCEEDED, 1, 1, 0);
    }

    @Test
    @DisplayName("대상 조회가 실패하면 예외를 던지지 않고 FAILED로 기록한다")
    void failed() {
        when(settlementRepository.findReconciliationTargets(any(LocalDateTime.class)))
                .thenThrow(new IllegalStateException("DB 연결 실패"));

        ReconciliationRunResult result = service(100).reconcile();

        assertThat(result).isEqualTo(ReconciliationRunResult.FAILED);
        verify(ledgerClient, never()).findLedgers(anyCollection());
        verify(runRepository).insert(eq(NOW), eq(NOW), eq(ReconciliationRunResult.FAILED), anyInt(), anyInt(), anyInt());
        assertThat(runs("failed")).isEqualTo(1);
        assertThat(runs("succeeded")).isZero();
    }

    @Test
    @DisplayName("불일치 기록이 실패해도 FAILED로 끝난다")
    void failedWhileRecording() {
        givenTargets(target(1L));
        when(ledgerClient.findLedgers(anyCollection())).thenReturn(Map.of(1L, List.of()));
        when(recorder.record(anyList(), any())).thenThrow(new IllegalStateException("DB 연결 실패"));

        assertThat(service(100).reconcile()).isEqualTo(ReconciliationRunResult.FAILED);
        assertThat(runs("failed")).isEqualTo(1);
    }

    private SettlementReconciliationService service(int batchSize) {
        Clock clock = Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL);
        SettlementReconciliationMetrics metrics = new SettlementReconciliationMetrics(
                mock(SettlementReconciliationMismatchRepository.class), runRepository, clock, registry);
        return new SettlementReconciliationService(settlementRepository, ledgerClient, recorder, runRepository,
                metrics, SettlementReconciliationPropertiesTest.withBatchSize(batchSize), clock,
                mock(PlatformTransactionManager.class));
    }

    private double runs(String result) {
        return registry.get("settlement.reconciliation.runs").tag("result", result).counter().count();
    }

    private void givenTargets(ReconciliationTarget... targets) {
        when(settlementRepository.findReconciliationTargets(any(LocalDateTime.class))).thenReturn(List.of(targets));
    }

    private static Map<Long, List<SettlementLedgerEntry>> ledgersFor(Collection<Long> ids) {
        return ids.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> paidLedger()));
    }

    private static ReconciliationTarget target(long settlementId) {
        return new ReconciliationTarget(settlementId, SELLER_ID, SettlementStatus.COMPLETED,
                new BigDecimal("1500"), BigDecimal.ZERO, new BigDecimal("28500"));
    }

    private static List<SettlementLedgerEntry> paidLedger() {
        LocalDateTime paidAt = NOW.minusHours(1);
        return List.of(
                new SettlementLedgerEntry(100L, "HOLDING", null, "SETTLEMENT", "OUT", new BigDecimal("30000"), paidAt),
                new SettlementLedgerEntry(200L, "REVENUE", null, "SETTLEMENT_FEE", "IN", new BigDecimal("1500"), paidAt),
                new SettlementLedgerEntry(1L, "MEMBER", SELLER_ID, "SETTLEMENT", "IN", new BigDecimal("28500"), paidAt));
    }
}
