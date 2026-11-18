package com.justcommit.backend.settlement.domain;

// 정산 지급 상태. PENDING: 지급 전, COMPLETED: 지급 완료 또는 회수 요청 성공, FAILED: 지급 실패(다음 시도 대기)
public enum SettlementStatus {
    PENDING,
    COMPLETED,
    FAILED
}
