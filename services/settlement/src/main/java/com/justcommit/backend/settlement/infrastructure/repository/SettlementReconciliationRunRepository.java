package com.justcommit.backend.settlement.infrastructure.repository;

import com.justcommit.backend.settlement.domain.reconciliation.ReconciliationRunResult;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

// 정산 대사 실행 이력 (settlement_reconciliation_run, V7). 마지막 성공 시각을 재시작 뒤에도 읽으려고 DB에 둔다.
// result = 'BASELINE' 행은 실행이 아니라 배포 시각 기준 행이다. 실행 통계를 낼 때는 빼고 읽는다
@Repository
@RequiredArgsConstructor
public class SettlementReconciliationRunRepository {

    private final JdbcTemplate jdbcTemplate;

    public void insert(LocalDateTime startedAt, LocalDateTime finishedAt, ReconciliationRunResult result,
                       int targetCount, int mismatchCount, int failedBatchCount) {
        if (!result.isRunResult()) {
            throw new IllegalArgumentException("BASELINE은 실행 결과가 아니라 V7 마이그레이션만 넣습니다.");
        }
        jdbcTemplate.update("""
                INSERT INTO settlement_reconciliation_run
                       (started_at, finished_at, result, target_count, mismatch_count, failed_batch_count)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                Timestamp.valueOf(startedAt), Timestamp.valueOf(finishedAt), result.name(),
                targetCount, mismatchCount, failedBatchCount);
    }

    // 미실행 알림의 기준 시각: 마지막 SUCCEEDED 실행이 끝난 시각, 성공 이력이 없으면 BASELINE(배포 시각).
    // 둘 다 없으면(운영자가 테이블을 비운 경우) 비어 있음
    public Optional<LocalDateTime> findLastSuccessOrBaselineAt() {
        Timestamp finishedAt = jdbcTemplate.queryForObject("""
                SELECT max(finished_at)
                  FROM settlement_reconciliation_run
                 WHERE result IN ('SUCCEEDED', 'BASELINE')
                """, Timestamp.class);
        return Optional.ofNullable(finishedAt).map(Timestamp::toLocalDateTime);
    }
}
