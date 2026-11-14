package com.justcommit.backend.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementTest {

    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 11, 12, 10, 0);
    private static final LocalDateTime COMPLETED_AT = REQUESTED_AT.plusSeconds(10);
    private static final LocalDateTime NEXT_ATTEMPT_AT = REQUESTED_AT.plusSeconds(30);

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
    }

    private static Settlement settlement(BigDecimal shippingFee) {
        return Settlement.createSettlement(10L, 7L, new BigDecimal("30000"), shippingFee,
                REQUESTED_AT, "몬스테라");
    }
}
