package com.justcommit.backend.payment.presentation.internal.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

// 정산 원장 조회 요청. 한 번에 1~100개, 중복 ID는 서비스에서 하나로 합친다
public record SettlementLedgerRequest(
        @NotEmpty @Size(max = 100) List<@NotNull Long> settlementIds
) {
}
