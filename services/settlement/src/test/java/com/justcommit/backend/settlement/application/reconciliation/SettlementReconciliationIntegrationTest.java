package com.justcommit.backend.settlement.application.reconciliation;

import com.justcommit.backend.common.testcontainers.SharedPostgres;
import com.justcommit.backend.settlement.client.payment.SettlementLedgerClient;
import com.justcommit.backend.settlement.client.payment.SettlementLedgerEntry;
import com.justcommit.backend.settlement.domain.Settlement;
import com.justcommit.backend.settlement.domain.SettlementStatus;
import com.justcommit.backend.settlement.domain.reconciliation.MismatchType;
import com.justcommit.backend.settlement.domain.reconciliation.ReconciliationRunResult;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementReconciliationMismatchRepository;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementReconciliationRunRepository;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

// 정산 대사를 Flyway 스키마와 실제 PostgreSQL에서 확인한다 (payment 원장 조회만 목)
//   대상 조회 범위, 미해결 행에만 걸린 부분 유니크, 기록 직전 상태 재확인, DB에서 읽는 지표, 실행 이력
// 정산 지급 · 정산 대사 스케줄러와 Kafka 리스너는 끈다 (백그라운드 실행이 테스트 데이터를 바꾸지 않게)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SettlementReconciliationIntegrationTest {

    private static final long SELLER_ID = 7L;

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
        registry.add("jwt.public-key", SettlementReconciliationIntegrationTest::newPublicKey);
        registry.add("internal-api.key", () -> "test-internal-key");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("custom.settlement.payout.enabled", () -> "false");
        registry.add("custom.settlement.reconciliation.enabled", () -> "false");
        registry.add("outbox.scheduler.enabled", () -> "false");
    }

    @Autowired SettlementReconciliationService reconciliationService;
    @Autowired ReconciliationMismatchRecorder recorder;
    @Autowired SettlementRepository settlementRepository;
    @Autowired SettlementReconciliationMismatchRepository mismatchRepository;
    @Autowired SettlementReconciliationRunRepository runRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MeterRegistry meterRegistry;
    @MockitoBean SettlementLedgerClient ledgerClient;

    private long sellerOrderSeq = 0;

    @BeforeEach
    void cleanUp() {
        settlementRepository.deleteAll();
        jdbcTemplate.execute("truncate table settlement_reconciliation_mismatch, settlement_reconciliation_run");
    }

    @Test
    @DisplayName("대상: PENDING, FAILED는 모두, COMPLETED는 completed_at이 기준 시각 이후인 것만 (경계 포함)")
    void findsTargets() {
        LocalDateTime since = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).minusDays(7);
        Settlement oldPending = saveSettlement();
        setCreatedAt(oldPending, since.minusDays(30));
        Settlement failed = saveSettlement();
        setStatus(failed, SettlementStatus.FAILED, null);
        Settlement recent = saveSettlement();
        setStatus(recent, SettlementStatus.COMPLETED, since.plusDays(1));
        Settlement boundary = saveSettlement();
        setStatus(boundary, SettlementStatus.COMPLETED, since);
        Settlement old = saveSettlement();
        setStatus(old, SettlementStatus.COMPLETED, since.minusSeconds(1));

        List<ReconciliationTarget> targets = settlementRepository.findReconciliationTargets(since);

        assertThat(targets).extracting(ReconciliationTarget::settlementId)
                .containsExactly(oldPending.getId(), failed.getId(), recent.getId(), boundary.getId());
        assertThat(targets.getFirst()).isEqualTo(new ReconciliationTarget(oldPending.getId(), SELLER_ID,
                SettlementStatus.PENDING, new BigDecimal("1500"), BigDecimal.ZERO, new BigDecimal("28500")));
    }

    @Test
    @DisplayName("부분 유니크: 미해결 행이 있으면 다시 기록하지 않고, 해결한 뒤 같은 불일치가 다시 생기면 새 행으로 기록한다")
    void partialUniqueAllowsRecurrenceAfterResolve() {
        Settlement settlement = saveSettlement();
        setStatus(settlement, SettlementStatus.COMPLETED, LocalDateTime.now());
        DetectedMismatch mismatch = new DetectedMismatch(settlement.getId(), MismatchType.AMOUNT_MISMATCH,
                "MEMBER SETTLEMENT IN 28500", "MEMBER SETTLEMENT IN 28000");
        LocalDateTime firstRun = LocalDateTime.now().minusDays(1);
        LocalDateTime secondRun = LocalDateTime.now();

        // 같은 불일치를 두 번 찾아도 미해결 행은 하나
        assertThat(recorder.record(List.of(mismatch), firstRun).inserted()).isEqualTo(1);
        assertThat(recorder.record(List.of(mismatch), firstRun).alreadyRecorded()).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(1);

        // 운영자가 SQL로 해결 처리
        jdbcTemplate.update("update settlement_reconciliation_mismatch set resolved_at = now() where settlement_id = ?",
                settlement.getId());
        assertThat(mismatchRepository.countUnresolved()).isZero();

        // 같은 불일치가 다시 생기면 새 행
        assertThat(recorder.record(List.of(mismatch), secondRun).inserted()).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(2);
        assertThat(mismatchRepository.countUnresolved()).isEqualTo(1);

        // 그 뒤 다시 찾아도 그대로 2행
        assertThat(recorder.record(List.of(mismatch), secondRun).alreadyRecorded()).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 정산이어도 유형이 다르면 따로 기록한다")
    void differentTypesRecordedSeparately() {
        Settlement settlement = saveSettlement();
        LocalDateTime now = LocalDateTime.now();

        recorder.record(List.of(
                new DetectedMismatch(settlement.getId(), MismatchType.AMOUNT_MISMATCH, "a", "b"),
                new DetectedMismatch(settlement.getId(), MismatchType.UNREFLECTED_STATUS, "COMPLETED", "PENDING")), now);

        assertThat(mismatchRepository.countUnresolved()).isEqualTo(2);
    }

    @Test
    @DisplayName("기록 직전 상태 재확인: 대사 도중 지급이 성공해 COMPLETED가 된 정산은 UNREFLECTED_STATUS로 기록하지 않는다")
    void recheckSkipsCompletedSettlement() {
        Settlement stillPending = saveSettlement();
        Settlement completedMeanwhile = saveSettlement();
        // 판정은 PENDING일 때 했고, 기록 전에 정산 지급 재시도가 성공한 상황
        setStatus(completedMeanwhile, SettlementStatus.COMPLETED, LocalDateTime.now());

        ReconciliationMismatchRecorder.RecordOutcome outcome = recorder.record(List.of(
                new DetectedMismatch(stillPending.getId(), MismatchType.UNREFLECTED_STATUS, "COMPLETED", "PENDING"),
                new DetectedMismatch(completedMeanwhile.getId(), MismatchType.UNREFLECTED_STATUS, "COMPLETED", "PENDING"),
                // 상태와 상관없는 유형은 다시 읽지 않고 기록
                new DetectedMismatch(completedMeanwhile.getId(), MismatchType.AMOUNT_MISMATCH, "a", "b")),
                LocalDateTime.now());

        assertThat(outcome).isEqualTo(new ReconciliationMismatchRecorder.RecordOutcome(2, 0, 1));
        assertThat(jdbcTemplate.queryForList(
                "select settlement_id, mismatch_type from settlement_reconciliation_mismatch order by id"))
                .extracting(row -> List.of(((Number) row.get("settlement_id")).longValue(), row.get("mismatch_type")))
                .containsExactly(
                        List.of(stillPending.getId(), "UNREFLECTED_STATUS"),
                        List.of(completedMeanwhile.getId(), "AMOUNT_MISMATCH"));
    }

    @Test
    @DisplayName("실행 전체: payment는 지급했는데 PENDING인 정산을 UNREFLECTED_STATUS로 기록하고 실행 이력과 지표를 남긴다")
    void reconcileEndToEnd() {
        Settlement pending = saveSettlement();
        Settlement completed = saveSettlement();
        setStatus(completed, SettlementStatus.COMPLETED, LocalDateTime.now().minusHours(1));
        LocalDateTime paidAt = LocalDateTime.now().minusMinutes(30);
        when(ledgerClient.findLedgers(anyCollection())).thenReturn(Map.of(
                pending.getId(), paidLedger(paidAt),
                completed.getId(), paidLedger(paidAt)));

        ReconciliationRunResult result = reconciliationService.reconcile();

        assertThat(result).isEqualTo(ReconciliationRunResult.SUCCEEDED);
        assertThat(jdbcTemplate.queryForList("select settlement_id, mismatch_type from settlement_reconciliation_mismatch"))
                .singleElement().satisfies(row -> {
                    assertThat(((Number) row.get("settlement_id")).longValue()).isEqualTo(pending.getId());
                    assertThat(row.get("mismatch_type")).isEqualTo("UNREFLECTED_STATUS");
                });
        Map<String, Object> run = jdbcTemplate.queryForMap("select * from settlement_reconciliation_run");
        assertThat(run.get("result")).isEqualTo("SUCCEEDED");
        assertThat(run.get("target_count")).isEqualTo(2);
        assertThat(run.get("mismatch_count")).isEqualTo(1);
        assertThat(run.get("failed_batch_count")).isEqualTo(0);

        assertThat(gauge("settlement.reconciliation.mismatches")).isEqualTo(1.0);
        LocalDateTime finishedAt = ((Timestamp) run.get("finished_at")).toLocalDateTime();
        assertThat(gauge("settlement.reconciliation.last.success.timestamp"))
                .isEqualTo(finishedAt.atZone(ZoneId.systemDefault()).toEpochSecond());
    }

    @Test
    @DisplayName("마지막 성공 시각은 SUCCEEDED 실행만 본다 (PARTIAL, FAILED는 미실행 알림을 막지 않음)")
    void lastSuccessIgnoresPartialAndFailed() {
        assertThat(gauge("settlement.reconciliation.last.success.timestamp")).isNaN();

        LocalDateTime succeededAt = LocalDateTime.of(2026, 11, 24, 9, 40, 3);
        runRepository.insert(succeededAt.minusSeconds(3), succeededAt, ReconciliationRunResult.SUCCEEDED, 1, 0, 0);
        runRepository.insert(succeededAt.plusDays(1), succeededAt.plusDays(1), ReconciliationRunResult.PARTIAL, 1, 0, 1);
        runRepository.insert(succeededAt.plusDays(1), succeededAt.plusDays(1), ReconciliationRunResult.FAILED, 0, 0, 0);

        assertThat(runRepository.findLastSuccessOrBaselineAt()).contains(succeededAt);
        assertThat(gauge("settlement.reconciliation.last.success.timestamp"))
                .isEqualTo(succeededAt.atZone(ZoneId.systemDefault()).toEpochSecond());
    }

    @Test
    @DisplayName("성공 이력이 없으면 기준 행(BASELINE, 배포 시각)을 마지막 성공 시각으로 쓰고, 성공하면 그 시각으로 바뀐다")
    void baselineUsedUntilFirstSuccess() {
        LocalDateTime deployedAt = LocalDateTime.of(2026, 11, 25, 14, 0);
        insertBaseline(deployedAt);
        // 실패, 일부 실패는 기준 시각을 앞당기지 않는다
        runRepository.insert(deployedAt.plusHours(20), deployedAt.plusHours(20), ReconciliationRunResult.FAILED, 0, 0, 0);
        runRepository.insert(deployedAt.plusHours(21), deployedAt.plusHours(21), ReconciliationRunResult.PARTIAL, 2, 0, 1);

        assertThat(runRepository.findLastSuccessOrBaselineAt()).contains(deployedAt);
        assertThat(gauge("settlement.reconciliation.last.success.timestamp"))
                .isEqualTo(deployedAt.atZone(ZoneId.systemDefault()).toEpochSecond());

        LocalDateTime succeededAt = deployedAt.plusHours(22);
        runRepository.insert(succeededAt, succeededAt, ReconciliationRunResult.SUCCEEDED, 1, 0, 0);

        assertThat(runRepository.findLastSuccessOrBaselineAt()).contains(succeededAt);
    }

    @Test
    @DisplayName("BASELINE은 마이그레이션만 넣는다 (실행 결과로 기록할 수 없음)")
    void baselineCannotBeRecordedAsRun() {
        LocalDateTime now = LocalDateTime.now();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        runRepository.insert(now, now, ReconciliationRunResult.BASELINE, 0, 0, 0))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BASELINE");
        assertThat(jdbcTemplate.queryForObject("select count(*) from settlement_reconciliation_run", Integer.class))
                .isZero();
    }

    private void insertBaseline(LocalDateTime at) {
        jdbcTemplate.update("""
                insert into settlement_reconciliation_run
                       (started_at, finished_at, result, target_count, mismatch_count, failed_batch_count)
                values (?, ?, 'BASELINE', 0, 0, 0)
                """, Timestamp.valueOf(at), Timestamp.valueOf(at));
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    private Integer rowCount() {
        return jdbcTemplate.queryForObject("select count(*) from settlement_reconciliation_mismatch", Integer.class);
    }

    private static List<SettlementLedgerEntry> paidLedger(LocalDateTime paidAt) {
        return List.of(
                new SettlementLedgerEntry(100L, "HOLDING", null, "SETTLEMENT", "OUT", new BigDecimal("30000"), paidAt),
                new SettlementLedgerEntry(200L, "REVENUE", null, "SETTLEMENT_FEE", "IN", new BigDecimal("1500"), paidAt),
                new SettlementLedgerEntry(1L, "MEMBER", SELLER_ID, "SETTLEMENT", "IN", new BigDecimal("28500"), paidAt));
    }

    // 판매액 30000, 수수료 1500, 부담금 0 → 정산금 28500 (PENDING)
    private Settlement saveSettlement() {
        return settlementRepository.save(Settlement.createSettlement(++sellerOrderSeq, SELLER_ID,
                new BigDecimal("30000"), new BigDecimal("3000"), BigDecimal.ZERO, LocalDateTime.now(), "몬스테라"));
    }

    private void setStatus(Settlement settlement, SettlementStatus status, LocalDateTime completedAt) {
        jdbcTemplate.update("update settlement set status = ?, completed_at = ? where id = ?",
                status.name(), completedAt == null ? null : Timestamp.valueOf(completedAt), settlement.getId());
    }

    private void setCreatedAt(Settlement settlement, LocalDateTime createdAt) {
        jdbcTemplate.update("update settlement set created_at = ? where id = ?",
                Timestamp.valueOf(createdAt), settlement.getId());
    }

    private static String newPublicKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
