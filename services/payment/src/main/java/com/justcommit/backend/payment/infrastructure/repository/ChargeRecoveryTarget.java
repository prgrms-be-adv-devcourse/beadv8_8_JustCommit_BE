package com.justcommit.backend.payment.infrastructure.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 복구 스케줄러가 확보한 충전 한 건 (엔티티를 불러오지 않음)
// leasedUntil: 확보할 때 건 임대 값(next_attempt_at). 반영할 때 이 값이 그대로일 때만 바꾼다
public record ChargeRecoveryTarget(
        Long id,
        String pgOrderNo,
        BigDecimal amount,
        LocalDateTime createdAt,
        int attemptCount,
        LocalDateTime leasedUntil
) {
}
