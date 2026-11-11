package com.justcommit.backend.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementTest {

    @Test
    @DisplayName("배송비가 비어 있으면 받은 값으로 채운다")
    void fillsShippingFeeWhenAbsent() {
        Settlement settlement = settlement(null);

        settlement.updateShippingFeeIfAbsent(new BigDecimal("3000"));

        assertThat(settlement.getShippingFee()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("배송비가 이미 있으면 바꾸지 않는다")
    void keepsExistingShippingFee() {
        Settlement settlement = settlement(new BigDecimal("3000"));

        settlement.updateShippingFeeIfAbsent(new BigDecimal("5000"));

        assertThat(settlement.getShippingFee()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("받은 배송비가 없으면(null) 그대로 둔다")
    void ignoresNullShippingFee() {
        Settlement settlement = settlement(null);

        settlement.updateShippingFeeIfAbsent(null);

        assertThat(settlement.getShippingFee()).isNull();
    }

    private static Settlement settlement(BigDecimal shippingFee) {
        return Settlement.createSettlement(10L, 7L, new BigDecimal("30000"), shippingFee,
                LocalDateTime.of(2026, 11, 12, 10, 0), "몬스테라");
    }
}
