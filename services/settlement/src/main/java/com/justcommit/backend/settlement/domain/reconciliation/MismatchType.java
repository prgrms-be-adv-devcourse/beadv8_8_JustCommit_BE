package com.justcommit.backend.settlement.domain.reconciliation;

// 대사 불일치 유형 (docs/flows/confirm-settlement.md 「정산 대사」)
public enum MismatchType {
    // 판매자 회원 지갑, HOLDING, REVENUE가 아닌 지갑에 정산 원장이 있음
    UNEXPECTED_WALLET,
    // COMPLETED 정산인데 판매자 SETTLEMENT IN 원장이 없음 (지급 완료로 기록했지만 payment에는 지급이 없음)
    MISSING_LEDGER,
    // 기대한 원장 집합(지갑, 참조 유형, 방향, 금액, 건수)과 실제 원장이 다름
    AMOUNT_MISMATCH,
    // payment는 지급했는데 정산이 유예 시간 넘게 PENDING 또는 FAILED로 남음
    UNREFLECTED_STATUS
}
