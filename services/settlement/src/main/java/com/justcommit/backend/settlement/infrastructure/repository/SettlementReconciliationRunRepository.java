package com.justcommit.backend.settlement.infrastructure.repository;

import com.justcommit.backend.settlement.domain.reconciliation.ReconciliationRunResult;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

// 정산 대사 실행 이력 (settlement_reconciliation_run, V7). 마지막 성공 시각을 재시작 뒤에도 읽으려고 DB에 둔다
@Repository
@RequiredArgsConstructor
public class SettlementReconciliationRunRepository {

    private final JdbcTemplate jdbcTemplate;

    public void insert(LocalDateTime startedAt, LocalDateTime finishedAt, ReconciliationRunResult result,
                       int targetCount, int mismatchCount, int failedBatchCount) {
        jdbcTemplate.update("""
                INSERT INTO settlement_reconciliation_run
                       (started_at, finished_at, result, target_count, mismatch_count, failed_batch_count)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                Timestamp.valueOf(startedAt), Timestamp.valueOf(finishedAt), result.name(),
                targetCount, mismatchCount, failedBatchCount);
    }

    // 결과가 SUCCEEDED인 실행 중 가장 늦게 끝난 시각. 한 번도 성공하지 않았으면 비어 있음
    public Optional<LocalDateTime> findLastSucceededAt() {
        Timestamp finishedAt = jdbcTemplate.queryForObject(
                "SELECT max(finished_at) FROM settlement_reconciliation_run WHERE result = 'SUCCEEDED'",
                Timestamp.class);
        return Optional.ofNullable(finishedAt).map(Timestamp::toLocalDateTime);
    }
}
