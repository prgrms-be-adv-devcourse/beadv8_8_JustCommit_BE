package com.justcommit.backend.payment.domain;

public enum ChargeStatus {
    READY,
    PENDING,
    APPROVED,
    CANCELED,
    FAILED,
    // 토스 조회로도 결과를 확정하지 못해 사람이 확인해야 하는 충전 (복구 스케줄러가 더 보지 않음)
    REVIEW_REQUIRED
}
