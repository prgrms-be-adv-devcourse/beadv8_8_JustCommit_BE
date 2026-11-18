package com.justcommit.backend.market;

public enum OrderStatus {
    PAYMENT_PENDING,
    // payment에 결제를 요청했고 아직 결과를 반영하지 못함 (호출 중 · 결과 불명). 만료하지 않고 Saga 복구가 확정한다 (ADR 0023)
    PAYMENT_PROCESSING,
    PAID,
    PAYMENT_FAILED,
    EXPIRED,
    CANCELLED,
    COMPLETED,
    // 복구 5회로도 결제 결과를 확정하지 못함. 사람이 확인한다 (ADR 0023)
    REVIEW_REQUIRED,
}
