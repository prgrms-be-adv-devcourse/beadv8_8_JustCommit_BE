package com.justcommit.backend.market.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

// 끝난 판매자 주문의 정산 종류와 금액 (#657). 정산 이벤트와 정산 대상 조회 내부 API가 같이 쓰는 계산
class SellerOrderSettlementAmountsTest {

    @Test
    @DisplayName("구매 확정 1개 이상이면 PURCHASE_CONFIRMED: 판매액 = 정산 기준액, 배송비 그대로, 판매자 부담금 = 반품 배송비 합")
    void purchaseConfirmed() {
        SellerOrderSettlementAmounts amounts = SellerOrderSettlementAmounts.of(true, new BigDecimal("10000"),
                new BigDecimal("3000"), new BigDecimal("6000"));

        assertThat(amounts.type()).isEqualTo(SellerOrderSettlementAmounts.Type.PURCHASE_CONFIRMED);
        assertThat(amounts.saleAmount()).isEqualByComparingTo("10000");
        assertThat(amounts.shippingFee()).isEqualByComparingTo("3000");
        assertThat(amounts.sellerBurdenAmount()).isEqualByComparingTo("6000");
    }

    @Test
    @DisplayName("반품이 없으면 판매자 부담금은 0 (반품 배송비 합이 null이어도)")
    void purchaseConfirmedWithoutReturn() {
        SellerOrderSettlementAmounts amounts = SellerOrderSettlementAmounts.of(true, new BigDecimal("10000"),
                new BigDecimal("3000"), null);

        assertThat(amounts.sellerBurdenAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("구매 확정이 없으면(전부 반품) RETURNED: 판매액, 배송비 0, 판매자 부담금 = 반품 배송비 합 + 원래 배송비")
    void returned() {
        SellerOrderSettlementAmounts amounts = SellerOrderSettlementAmounts.of(false, new BigDecimal("0"),
                new BigDecimal("3000"), new BigDecimal("6000"));

        assertThat(amounts.type()).isEqualTo(SellerOrderSettlementAmounts.Type.RETURNED);
        assertThat(amounts.saleAmount()).isEqualByComparingTo("0");
        assertThat(amounts.shippingFee()).isEqualByComparingTo("0");
        assertThat(amounts.sellerBurdenAmount()).isEqualByComparingTo("9000");
    }
}
