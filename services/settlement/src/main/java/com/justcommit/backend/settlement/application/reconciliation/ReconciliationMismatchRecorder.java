package com.justcommit.backend.settlement.application.reconciliation;

import com.justcommit.backend.settlement.domain.reconciliation.MismatchType;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementReconciliationMismatchRepository;
import com.justcommit.backend.settlement.infrastructure.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// 판정기가 찾은 불일치를 기록한다. 배치마다 짧은 트랜잭션 하나
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationMismatchRecorder {

    private final SettlementRepository settlementRepository;
    private final SettlementReconciliationMismatchRepository mismatchRepository;

    // UNREFLECTED_STATUS는 기록 직전에 정산 상태를 FOR SHARE로 다시 읽어, 대사 도중 지급 재시도가 성공해
    // COMPLETED가 된 정산은 기록하지 않는다 (오탐 방지). 다른 유형은 원장만 보고 판정하므로 다시 읽지 않는다
    @Transactional
    public RecordOutcome record(List<DetectedMismatch> mismatches, LocalDateTime detectedAt) {
        if (mismatches.isEmpty()) {
            return RecordOutcome.NONE;
        }
        Set<Long> unreflectedIds = mismatches.stream()
                .filter(mismatch -> mismatch.type() == MismatchType.UNREFLECTED_STATUS)
                .map(DetectedMismatch::settlementId)
                .collect(Collectors.toSet());
        Set<Long> stillUnfinished = unreflectedIds.isEmpty()
                ? Set.of()
                : new HashSet<>(settlementRepository.lockUnfinishedIds(unreflectedIds));

        int inserted = 0;
        int alreadyRecorded = 0;
        int recheckSkipped = 0;
        for (DetectedMismatch mismatch : mismatches) {
            if (mismatch.type() == MismatchType.UNREFLECTED_STATUS
                    && !stillUnfinished.contains(mismatch.settlementId())) {
                recheckSkipped++;
                continue;
            }
            if (mismatchRepository.insertIfAbsent(mismatch, detectedAt)) {
                inserted++;
                // memberId는 남기지 않는다. 금액은 원장 요약(expected, actual)에만 있다
                log.atError()
                        .addKeyValue("settlementId", mismatch.settlementId())
                        .addKeyValue("mismatchType", mismatch.type())
                        .addKeyValue("expected", mismatch.expected())
                        .addKeyValue("actual", mismatch.actual())
                        .log("정산 대사 불일치를 찾았습니다.");
            } else {
                alreadyRecorded++;
            }
        }
        return new RecordOutcome(inserted, alreadyRecorded, recheckSkipped);
    }

    // inserted: 새로 기록, alreadyRecorded: 같은 미해결 행이 있어 건너뜀, recheckSkipped: 다시 읽어 보니 COMPLETED
    public record RecordOutcome(int inserted, int alreadyRecorded, int recheckSkipped) {
        static final RecordOutcome NONE = new RecordOutcome(0, 0, 0);
    }
}
