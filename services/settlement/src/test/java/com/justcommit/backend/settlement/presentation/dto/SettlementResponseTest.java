package com.justcommit.backend.settlement.presentation.dto;

import com.justcommit.backend.settlement.SettlementResult;
import com.justcommit.backend.settlement.domain.SettlementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementResponseTest {

    @Test
    @DisplayName("지급이 끝난 정산은 COMPLETED와 완료 날짜를 준다")
    void completed() {
        SettlementResponse response = SettlementResponse.from(
                result(SettlementStatus.COMPLETED, LocalDateTime.of(2026, 11, 12, 10, 0)));

        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getDate()).isEqualTo(LocalDate.of(2026, 11, 12));
    }

    @Test
    @DisplayName("지급 전 정산은 PENDING과 날짜 null을 준다")
    void pending() {
        SettlementResponse response = SettlementResponse.from(result(SettlementStatus.PENDING, null));

        assertThat(response.getStatus()).isEqualTo("PENDING");
        assertThat(response.getDate()).isNull();
    }

    @Test
    @DisplayName("지급에 실패한 정산은 다시 시도 중이라 판매자에게는 PENDING으로 보여 준다")
    void failedShownAsPending() {
        SettlementResponse response = SettlementResponse.from(result(SettlementStatus.FAILED, null));

        assertThat(response.getStatus()).isEqualTo("PENDING");
        assertThat(response.getDate()).isNull();
    }

    private static SettlementResult result(SettlementStatus status, LocalDateTime completedAt) {
        return new SettlementResult(1L, 10L, new BigDecimal("30000"), new BigDecimal("1500"),
                new BigDecimal("28500"), completedAt, status, "몬스테라");
    }
}
