package com.justcommit.backend.settlement.presentation.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalCheckResponseTest {

    @Test
    @DisplayName("완료되지 않은 정산이 0건이면 차단 없음 + 빈 사유 목록")
    void noIncompleteSettlement() {
        WithdrawalCheckResponse response = WithdrawalCheckResponse.of(0);

        assertThat(response.hasWithdrawalBlocker()).isFalse();
        assertThat(response.blockingReasons()).isEmpty();
        assertThat(response.details().incompleteSettlementCount()).isZero();
    }

    @Test
    @DisplayName("완료되지 않은 정산이 1건 이상이면 차단 + INCOMPLETE_SETTLEMENT")
    void incompleteSettlementBlocks() {
        WithdrawalCheckResponse response = WithdrawalCheckResponse.of(2);

        assertThat(response.hasWithdrawalBlocker()).isTrue();
        assertThat(response.blockingReasons()).containsExactly(WithdrawalBlockingReason.INCOMPLETE_SETTLEMENT);
        assertThat(response.details().incompleteSettlementCount()).isEqualTo(2);
    }
}
