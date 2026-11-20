package com.justcommit.backend.payment.presentation.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

// 금액 검증(0보다 큰 원 단위)은 서비스에서 INVALID_AMOUNT로 한다
public record CashOutRequest(@NotNull BigDecimal amount) {
}
