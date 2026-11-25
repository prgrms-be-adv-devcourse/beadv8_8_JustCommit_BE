package com.justcommit.backend.settlement.infrastructure.repository;

import com.justcommit.backend.settlement.application.reconciliation.DetectedMismatch;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

// 대사 불일치 기록 (settlement_reconciliation_mismatch, V6).
// 미해결 행에만 걸린 부분 유니크 인덱스로 같은 불일치를 한 행만 남긴다. 해결(resolved_at)은 운영자가 SQL로 채운다
@Repository
@RequiredArgsConstructor
public class SettlementReconciliationMismatchRepository {

    private final JdbcTemplate jdbcTemplate;

    // 같은 정산, 같은 유형의 미해결 행이 이미 있으면 기록하지 않고 false
    public boolean insertIfAbsent(DetectedMismatch mismatch, LocalDateTime detectedAt) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO settlement_reconciliation_mismatch
                       (settlement_id, mismatch_type, expected, actual, detected_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (settlement_id, mismatch_type) WHERE resolved_at IS NULL DO NOTHING
                """,
                mismatch.settlementId(), mismatch.type().name(), mismatch.expected(), mismatch.actual(),
                Timestamp.valueOf(detectedAt));
        return inserted == 1;
    }

    public long countUnresolved() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM settlement_reconciliation_mismatch WHERE resolved_at IS NULL", Long.class);
        return count == null ? 0 : count;
    }
}
