package com.justcommit.backend.settlement.application.reconciliation;

import com.justcommit.backend.settlement.domain.reconciliation.MismatchType;

// 판정기가 찾은 불일치 한 건. expected, actual은 사람이 읽는 요약이며 memberId는 넣지 않는다
public record DetectedMismatch(
        Long settlementId,
        MismatchType type,
        String expected,
        String actual
) {
}
