package com.justcommit.backend.settlement;

import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.settlement.domain.Settlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementCompletedEventTest {

    private static final long SETTLEMENT_ID = 99L;
    private static final long SELLER_ID = 7L;
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 11, 20, 10, 0);
    private static final LocalDateTime COMPLETED_AT = REQUESTED_AT.plusSeconds(10);

    @Test
    @DisplayName("COMPLETED 정산에서 만들면 정산 값을 그대로 담고, 토픽 settlement.events.v1 · Key sellerId로 나간다")
    void fromCompletedSettlement() {
        Settlement settlement = settlement("30000", "6000");
        settlement.complete(COMPLETED_AT);

        SettlementCompletedEvent event = SettlementCompletedEvent.from(settlement);

        assertThat(event.settlementId()).isEqualTo(SETTLEMENT_ID);
        assertThat(event.sellerOrderId()).isEqualTo(1L);
        assertThat(event.sellerId()).isEqualTo(SELLER_ID);
        assertThat(event.saleAmount()).isEqualByComparingTo("30000");
        assertThat(event.fee()).isEqualByComparingTo("1500");
        assertThat(event.sellerBurdenAmount()).isEqualByComparingTo("6000");
        assertThat(event.payoutAmount()).isEqualByComparingTo("22500");
        assertThat(event.itemSummary()).isEqualTo("몬스테라 외 1건");
        assertThat(event.completedAt()).isEqualTo(COMPLETED_AT);
        assertThat(event.topic()).isEqualTo(KafkaTopics.SETTLEMENT_EVENTS);
        assertThat(event.eventType()).isEqualTo("SettlementCompleted");
        assertThat(event.aggregateId()).isEqualTo(String.valueOf(SELLER_ID));
    }

    @Test
    @DisplayName("음수 정산(회수)도 같은 이벤트로 만들고 payoutAmount가 음수다")
    void negativeSettlement() {
        Settlement settlement = settlement("10000", "12000");
        settlement.complete(COMPLETED_AT);

        assertThat(SettlementCompletedEvent.from(settlement).payoutAmount()).isEqualByComparingTo("-2500");
    }

    @Test
    @DisplayName("PENDING · FAILED 정산으로는 만들 수 없다")
    void rejectsNotCompleted() {
        Settlement pending = settlement("30000", "0");
        Settlement failed = settlement("30000", "0");
        failed.fail("CONNECTION_FAILED", REQUESTED_AT.plusMinutes(1));

        assertThatThrownBy(() -> SettlementCompletedEvent.from(pending)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SettlementCompletedEvent.from(failed)).isInstanceOf(IllegalStateException.class);
    }

    private static Settlement settlement(String saleAmount, String sellerBurdenAmount) {
        Settlement settlement = Settlement.createSettlement(1L, SELLER_ID, new BigDecimal(saleAmount),
                new BigDecimal("3000"), new BigDecimal(sellerBurdenAmount), REQUESTED_AT, "몬스테라 외 1건");
        ReflectionTestUtils.setField(settlement, "id", SETTLEMENT_ID);
        return settlement;
    }
}
