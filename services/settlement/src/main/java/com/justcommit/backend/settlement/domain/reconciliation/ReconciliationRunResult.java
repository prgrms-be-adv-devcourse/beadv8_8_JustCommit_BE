package com.justcommit.backend.settlement.domain.reconciliation;

// 정산 대사 한 번의 실행 결과 (settlement_reconciliation_run.result)
public enum ReconciliationRunResult {
    // 실패한 배치 없이 끝남. 마지막 성공 시각 지표는 이 결과만 본다
    SUCCEEDED,
    // 일부 배치의 payment 조회가 실패해 그 배치를 건너뜀
    PARTIAL,
    // 대상 조회 실패 같은 예상하지 못한 예외로 중단
    FAILED
}
