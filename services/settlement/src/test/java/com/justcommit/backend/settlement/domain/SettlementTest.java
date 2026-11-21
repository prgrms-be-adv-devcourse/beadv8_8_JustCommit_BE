package com.justcommit.backend.settlement.domain;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.settlement.domain.exception.SettlementErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementTest {

    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 11, 12, 10, 0);
    private static final LocalDateTime COMPLETED_AT = REQUESTED_AT.plusSeconds(10);
    private static final LocalDateTime NEXT_ATTEMPT_AT = REQUESTED_AT.plusSeconds(30);

    @Nested
    @DisplayName("정산금 = 판매액 - 수수료 - 판매자 부담금")
    class PayoutAmount {

        @Test
        @DisplayName("부담금이 0이면 기존과 같다: 판매액 30000 → 수수료 1500, 정산금 28500")
        void noBurdenKeepsPreviousResult() {
            Settlement settlement = withBurden("30000", "0");

            assertThat(settlement.getFee()).isEqualByComparingTo("1500");
            assertThat(settlement.getSellerBurdenAmount()).isEqualByComparingTo("0");
            assertThat(settlement.getPayoutAmount()).isEqualByComparingTo("28500");
        }

        @Test
        @DisplayName("일부 반품: 부담금 6000이면 수수료는 판매액에만 매겨 1500, 정산금 22500")
        void partialReturnBurden() {
            Settlement settlement = withBurden("30000", "6000");

            assertThat(settlement.getFee()).isEqualByComparingTo("1500");
            assertThat(settlement.getSellerBurdenAmount()).isEqualByComparingTo("6000");
            assertThat(settlement.getPayoutAmount()).isEqualByComparingTo("22500");
            assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        }

        @Test
        @DisplayName("부담금이 판매액 - 수수료와 같으면 정산금은 정확히 0원이고 PENDING으로 만든다")
        void zeroPayout() {
            Settlement settlement = withBurden("30000", "28500");

            assertThat(settlement.getPayoutAmount().signum()).isZero();
            assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        }

        @Test
        @DisplayName("부담금이 더 크면 정산금은 음수이고 PENDING으로 만든다")
        void negativePayout() {
            Settlement settlement = withBurden("10000", "12000");

            assertThat(settlement.getFee()).isEqualByComparingTo("500");
            assertThat(settlement.getPayoutAmount()).isEqualByComparingTo("-2500");
            assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        }

        @Test
        @DisplayName("부담금이 음수면 거절한다")
        void rejectsNegativeBurden() {
            assertThatThrownBy(() -> withBurden("30000", "-1"))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(SettlementErrorCode.INVALID_SELLER_BURDEN_AMOUNT));
        }

        @Test
        @DisplayName("부담금이 null이면 거절한다 (호출하는 쪽이 0으로 넘김)")
        void rejectsNullBurden() {
            assertThatThrownBy(() -> Settlement.createSettlement(10L, 7L, new BigDecimal("30000"), null, null,
                    REQUESTED_AT, "몬스테라"))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(SettlementErrorCode.INVALID_SELLER_BURDEN_AMOUNT));
        }

        @Test
        @DisplayName("구매 확정 정산은 판매액이 0이면 여전히 거절한다 (전부 반품은 createReturnedSettlement로만)")
        void rejectsZeroSaleAmount() {
            assertThatThrownBy(() -> withBurden("0", "0"))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(SettlementErrorCode.INVALID_AMOUNT));
        }

        private static Settlement withBurden(String saleAmount, String sellerBurdenAmount) {
            return Settlement.createSettlement(10L, 7L, new BigDecimal(saleAmount), new BigDecimal("3000"),
                    new BigDecimal(sellerBurdenAmount), REQUESTED_AT, "몬스테라");
        }
    }

    @Nested
    @DisplayName("전부 반품 정산")
    class ReturnedSettlement {

        @Test
        @DisplayName("판매액, 수수료, 배송비는 0이고 정산금은 -부담금인 PENDING 정산을 요청 시각부터 지급 대상으로 만든다")
        void createsNegativeSettlement() {
            Settlement settlement = Settlement.createReturnedSettlement(10L, 7L, new BigDecimal("9000"),
                    REQUESTED_AT, "몬스테라 외 1건");

            assertThat(settlement.getSaleAmount()).isEqualByComparingTo("0");
            assertThat(settlement.getFee()).isEqualByComparingTo("0");
            assertThat(settlement.getShippingFee()).isEqualByComparingTo("0");
            assertThat(settlement.getSellerBurdenAmount()).isEqualByComparingTo("9000");
            assertThat(settlement.getPayoutAmount()).isEqualByComparingTo("-9000");
            assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
            assertThat(settlement.getAttemptCount()).isZero();
            assertThat(settlement.getNextAttemptAt()).isEqualTo(REQUESTED_AT);
            assertThat(settlement.getItemSummary()).isEqualTo("몬스테라 외 1건");
        }

        @Test
        @DisplayName("배송비가 0원인 판매자 주문이라 부담금이 0이면 정산금 0인 정산을 만든다")
        void zeroBurdenMakesZeroPayout() {
            Settlement settlement = Settlement.createReturnedSettlement(10L, 7L, BigDecimal.ZERO,
                    REQUESTED_AT, "몬스테라");

            assertThat(settlement.getSellerBurdenAmount()).isEqualByComparingTo("0");
            assertThat(settlement.getPayoutAmount()).isEqualByComparingTo("0");
            assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        }

        @Test
        @DisplayName("부담금이 음수나 null이면 거절한다")
        void rejectsInvalidBurden() {
            assertThatThrownBy(() -> Settlement.createReturnedSettlement(10L, 7L, new BigDecimal("-1"),
                    REQUESTED_AT, "몬스테라"))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(SettlementErrorCode.INVALID_SELLER_BURDEN_AMOUNT));
            assertThatThrownBy(() -> Settlement.createReturnedSettlement(10L, 7L, null, REQUESTED_AT, "몬스테라"))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(SettlementErrorCode.INVALID_SELLER_BURDEN_AMOUNT));
        }
    }

    @Nested
    @DisplayName("배송비")
    class ShippingFee {

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
    }

    @Nested
    @DisplayName("지급 상태")
    class Status {

        @Test
        @DisplayName("정산은 PENDING으로 만들어지고 요청 시각부터 지급 대상이다")
        void createsPending() {
            Settlement settlement = settlement(null);

            assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
            assertThat(settlement.getAttemptCount()).isZero();
            assertThat(settlement.getNextAttemptAt()).isEqualTo(REQUESTED_AT);
            assertThat(settlement.getCompletedAt()).isNull();
            assertThat(settlement.getLastError()).isNull();
        }

        @Test
        @DisplayName("지급에 성공하면 COMPLETED가 되고 완료 시각을 남기며 다음 시도 시각을 비운다")
        void completes() {
            Settlement settlement = settlement(null);

            settlement.complete(COMPLETED_AT);

            assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
            assertThat(settlement.getCompletedAt()).isEqualTo(COMPLETED_AT);
            assertThat(settlement.getNextAttemptAt()).isNull();
        }

        @Test
        @DisplayName("지급에 실패하면 FAILED가 되고 시도 횟수, 사유, 다음 시도 시각을 남긴다")
        void fails() {
            Settlement settlement = settlement(null);

            settlement.fail("PAYMENT_TIMEOUT", NEXT_ATTEMPT_AT);

            assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.FAILED);
            assertThat(settlement.getAttemptCount()).isEqualTo(1);
            assertThat(settlement.getLastError()).isEqualTo("PAYMENT_TIMEOUT");
            assertThat(settlement.getNextAttemptAt()).isEqualTo(NEXT_ATTEMPT_AT);
            assertThat(settlement.getCompletedAt()).isNull();
        }

        @Test
        @DisplayName("실패한 정산이 다시 지급에 성공하면 COMPLETED가 되고 실패 사유를 비운다")
        void completesAfterFailure() {
            Settlement settlement = settlement(null);
            settlement.fail("PAYMENT_TIMEOUT", NEXT_ATTEMPT_AT);

            settlement.complete(COMPLETED_AT);

            assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
            assertThat(settlement.getLastError()).isNull();
            assertThat(settlement.getNextAttemptAt()).isNull();
            assertThat(settlement.getAttemptCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("COMPLETED 정산에 늦게 온 실패 결과는 반영하지 않는다")
        void ignoresFailureAfterCompleted() {
            Settlement settlement = settlement(null);
            settlement.complete(COMPLETED_AT);

            settlement.fail("PAYMENT_TIMEOUT", NEXT_ATTEMPT_AT);

            assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
            assertThat(settlement.getCompletedAt()).isEqualTo(COMPLETED_AT);
            assertThat(settlement.getAttemptCount()).isZero();
            assertThat(settlement.getLastError()).isNull();
            assertThat(settlement.getNextAttemptAt()).isNull();
        }

        @Test
        @DisplayName("이미 COMPLETED면 다시 완료해도 처음 완료 시각을 유지한다")
        void keepsFirstCompletedAt() {
            Settlement settlement = settlement(null);
            settlement.complete(COMPLETED_AT);

            settlement.complete(COMPLETED_AT.plusMinutes(1));

            assertThat(settlement.getCompletedAt()).isEqualTo(COMPLETED_AT);
        }

        @Test
        @DisplayName("실패 사유가 500자를 넘으면 500자까지만 남긴다")
        void truncatesLongError() {
            Settlement settlement = settlement(null);

            settlement.fail("e".repeat(600), NEXT_ATTEMPT_AT);

            assertThat(settlement.getLastError()).hasSize(500);
        }

        @Test
        @DisplayName("임대하면 다음 시도 시각이 임대 시각이 되고, 임대가 그대로일 때만 isLeasedUntil이 참이다")
        void leases() {
            Settlement settlement = settlement(null);
            LocalDateTime leasedUntil = REQUESTED_AT.plusSeconds(30);

            settlement.lease(leasedUntil);

            assertThat(settlement.getNextAttemptAt()).isEqualTo(leasedUntil);
            assertThat(settlement.isLeasedUntil(leasedUntil)).isTrue();
            assertThat(settlement.isLeasedUntil(leasedUntil.plusSeconds(1))).isFalse();
        }

        @Test
        @DisplayName("COMPLETED 정산은 임대하지 않고 isLeasedUntil도 거짓이다")
        void doesNotLeaseCompleted() {
            Settlement settlement = settlement(null);
            settlement.complete(COMPLETED_AT);

            settlement.lease(NEXT_ATTEMPT_AT);

            assertThat(settlement.getNextAttemptAt()).isNull();
            assertThat(settlement.isLeasedUntil(NEXT_ATTEMPT_AT)).isFalse();
        }
    }

    private static Settlement settlement(BigDecimal shippingFee) {
        return Settlement.createSettlement(10L, 7L, new BigDecimal("30000"), shippingFee, BigDecimal.ZERO,
                REQUESTED_AT, "몬스테라");
    }
}
