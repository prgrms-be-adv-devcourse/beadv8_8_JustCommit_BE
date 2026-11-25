package com.justcommit.backend.settlement.domain.reconciliation;

// 정산 대사 한 번의 실행 결과 (settlement_reconciliation_run.result)
public enum ReconciliationRunResult {
    // 실패한 배치 없이 끝남. 마지막 성공 시각 지표는 이 결과만 본다
    SUCCEEDED,
    // 일부 배치의 payment 조회가 실패해 그 배치를 건너뜀
    PARTIAL,
    // 모든 배치의 payment 조회가 실패했거나, 대상 조회 실패 같은 예상하지 못한 예외로 중단
    FAILED,
    // 실행 결과가 아니다. V7 마이그레이션이 배포 시각으로 한 번 넣는 기준 행으로,
    // 성공 이력이 없을 때 미실행 알림의 기준 시각이 된다. 실행 횟수 지표와 실행 통계에서는 뺀다
    BASELINE;

    // 대사를 실제로 돌린 결과인지 (BASELINE이 아닌지)
    public boolean isRunResult() {
        return this != BASELINE;
    }
}
