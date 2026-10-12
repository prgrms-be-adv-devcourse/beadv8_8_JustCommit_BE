package com.justcommit.backend.settlement.domain;

import com.justcommit.backend.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementTest {

    private static final Long SELLER_ORDER_ID = 100L;
    private static final LocalDateTime SETTLED_AT = LocalDateTime.of(2026, 10, 12, 15, 0);

    @Test
    void calculatesFeeAndPayoutAmount() {
        Settlement settlement = Settlement.createSettlement(
                SELLER_ORDER_ID, new BigDecimal("30000"), SETTLED_AT);

        assertThat(settlement.getSellerOrderId()).isEqualTo(SELLER_ORDER_ID);
        assertThat(settlement.getSaleAmount()).isEqualByComparingTo("30000");
        assertThat(settlement.getFee()).isEqualByComparingTo("1500");
        assertThat(settlement.getPayoutAmount()).isEqualByComparingTo("28500");
        assertThat(settlement.getSettledAt()).isEqualTo(SETTLED_AT);
    }

    @Test
    void truncatesFeeBelowOneWon() {
        // 12,345 × 5% = 617.25 → 617
        Settlement settlement = Settlement.createSettlement(
                SELLER_ORDER_ID, new BigDecimal("12345"), SETTLED_AT);

        assertThat(settlement.getFee()).isEqualByComparingTo("617");
        assertThat(settlement.getPayoutAmount()).isEqualByComparingTo("11728");
    }

    @Test
    void rejectsZeroSaleAmount() {
        assertThatThrownBy(() -> Settlement.createSettlement(
                SELLER_ORDER_ID, BigDecimal.ZERO, SETTLED_AT))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsNegativeSaleAmount() {
        assertThatThrownBy(() -> Settlement.createSettlement(
                SELLER_ORDER_ID, new BigDecimal("-1000"), SETTLED_AT))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsNullSaleAmount() {
        assertThatThrownBy(() -> Settlement.createSettlement(
                SELLER_ORDER_ID, null, SETTLED_AT))
                .isInstanceOf(BusinessException.class);
    }
}