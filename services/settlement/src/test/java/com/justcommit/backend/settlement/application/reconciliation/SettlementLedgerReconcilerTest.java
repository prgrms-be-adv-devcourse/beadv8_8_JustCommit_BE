package com.justcommit.backend.settlement.application.reconciliation;

import com.justcommit.backend.settlement.client.payment.SettlementLedgerEntry;
import com.justcommit.backend.settlement.domain.SettlementStatus;
import com.justcommit.backend.settlement.domain.reconciliation.MismatchType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 정산 대사 판정기: 기대 원장 계산, 불일치 네 유형, 유예 시간 경계
class SettlementLedgerReconcilerTest {

    private static final long SETTLEMENT_ID = 10L;
    private static final long SELLER_ID = 7L;
    private static final long OTHER_MEMBER_ID = 77777L;
    private static final Duration GRACE = Duration.ofMinutes(10);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 9, 40);
    // 판정 대상이 아닌 테스트의 지급 시각: 유예 시간 안
    private static final LocalDateTime RECENT = NOW.minusMinutes(1);

    @Test
    @DisplayName("양수 정산: HOLDING 판매액 OUT, REVENUE 수수료 IN, 판매자 정산금 IN이면 불일치 없음")
    void positiveSettlementMatches() {
        List<DetectedMismatch> result = reconcile(target(SettlementStatus.COMPLETED, "28500", "1500", "0"),
                holding("SETTLEMENT", "OUT", "30000"), revenue("SETTLEMENT_FEE", "IN", "1500"),
                seller("SETTLEMENT", "IN", "28500"));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("금액 자릿수(28500.00)가 달라도 같은 금액이면 불일치 없음")
    void amountScaleIgnored() {
        List<DetectedMismatch> result = reconcile(target(SettlementStatus.COMPLETED, "28500", "1500", "0"),
                holding("SETTLEMENT", "OUT", "30000.00"), revenue("SETTLEMENT_FEE", "IN", "1500.0"),
                seller("SETTLEMENT", "IN", "28500.00"));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("부담금이 있는 양수 정산은 REVENUE 판매자 부담금 IN도 있어야 한다")
    void positiveSettlementWithBurden() {
        ReconciliationTarget target = target(SettlementStatus.COMPLETED, "22500", "1500", "6000");

        assertThat(reconcile(target, holding("SETTLEMENT", "OUT", "30000"), revenue("SETTLEMENT_FEE", "IN", "1500"),
                seller("SETTLEMENT", "IN", "22500"), revenue("SELLER_BURDEN", "IN", "6000"))).isEmpty();
        assertThat(reconcile(target, holding("SETTLEMENT", "OUT", "30000"), revenue("SETTLEMENT_FEE", "IN", "1500"),
                seller("SETTLEMENT", "IN", "22500")))
                .extracting(DetectedMismatch::type).containsExactly(MismatchType.AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("0원 정산: 판매자 SETTLEMENT IN 0원 1건과 부담금 전액 IN이면 불일치 없음")
    void zeroSettlementMatches() {
        List<DetectedMismatch> result = reconcile(target(SettlementStatus.COMPLETED, "0", "1500", "28500"),
                holding("SETTLEMENT", "OUT", "30000"), revenue("SETTLEMENT_FEE", "IN", "1500"),
                seller("SETTLEMENT", "IN", "0"), revenue("SELLER_BURDEN", "IN", "28500"));

        assertThat(result).isEmpty();
    }

    @ParameterizedTest(name = "회수액 {0}이면 부담금 IN {1}")
    @CsvSource({
            "0,    9500",   // 판매자 잔액 0: 회수 행 없음, 전액 미수금
            "1000, 10500",  // 일부 회수
            "2500, 12000"   // 전액 회수 (c = |정산금|)
    })
    @DisplayName("음수 정산: 0 < 회수액 <= |정산금|이고 부담금 IN = 부담금 - |정산금| + 회수액이면 불일치 없음")
    void negativeSettlementMatches(String collected, String receivedBurden) {
        List<SettlementLedgerEntry> rows = new ArrayList<>(List.of(
                holding("SETTLEMENT", "OUT", "10000"), revenue("SETTLEMENT_FEE", "IN", "500"),
                seller("SETTLEMENT", "IN", "0"), revenue("SELLER_BURDEN", "IN", receivedBurden)));
        if (new BigDecimal(collected).signum() > 0) {
            rows.add(seller("SETTLEMENT_COLLECTION", "OUT", collected));
        }

        List<DetectedMismatch> result = SettlementLedgerReconciler.reconcile(
                target(SettlementStatus.COMPLETED, "-2500", "500", "12000"), rows, NOW, GRACE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("전부 반품이고 회수하지 못한 정산은 판매자 SETTLEMENT IN 0원 1건만 있으면 된다")
    void fullyReturnedUncollected() {
        List<DetectedMismatch> result = reconcile(target(SettlementStatus.COMPLETED, "-9000", "0", "9000"),
                seller("SETTLEMENT", "IN", "0"));

        assertThat(result).isEmpty();
    }

    @ParameterizedTest(name = "회수액 {0}")
    @CsvSource({"2501", "-1"})
    @DisplayName("음수 정산의 회수액이 0 < c <= |정산금| 밖이면 AMOUNT_MISMATCH")
    void collectionOutOfRange(String collected) {
        List<DetectedMismatch> result = reconcile(target(SettlementStatus.COMPLETED, "-2500", "500", "12000"),
                holding("SETTLEMENT", "OUT", "10000"), revenue("SETTLEMENT_FEE", "IN", "500"),
                seller("SETTLEMENT", "IN", "0"), seller("SETTLEMENT_COLLECTION", "OUT", collected),
                revenue("SELLER_BURDEN", "IN", "12000"));

        assertThat(result).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.type()).isEqualTo(MismatchType.AMOUNT_MISMATCH);
            assertThat(mismatch.expected()).contains("0 < 금액 <= 2500");
        });
    }

    @Test
    @DisplayName("음수 정산의 회수 행이 2건이면 AMOUNT_MISMATCH")
    void duplicatedCollection() {
        List<DetectedMismatch> result = reconcile(target(SettlementStatus.COMPLETED, "-2500", "500", "12000"),
                holding("SETTLEMENT", "OUT", "10000"), revenue("SETTLEMENT_FEE", "IN", "500"),
                seller("SETTLEMENT", "IN", "0"), seller("SETTLEMENT_COLLECTION", "OUT", "1000"),
                seller("SETTLEMENT_COLLECTION", "OUT", "1000"), revenue("SELLER_BURDEN", "IN", "11500"));

        assertThat(result).extracting(DetectedMismatch::type).containsExactly(MismatchType.AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("음수 정산에서 회수액과 부담금 IN이 맞지 않으면 AMOUNT_MISMATCH")
    void negativeBurdenMismatch() {
        List<DetectedMismatch> result = reconcile(target(SettlementStatus.COMPLETED, "-2500", "500", "12000"),
                holding("SETTLEMENT", "OUT", "10000"), revenue("SETTLEMENT_FEE", "IN", "500"),
                seller("SETTLEMENT", "IN", "0"), seller("SETTLEMENT_COLLECTION", "OUT", "1000"),
                revenue("SELLER_BURDEN", "IN", "9500"));

        assertThat(result).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.type()).isEqualTo(MismatchType.AMOUNT_MISMATCH);
            assertThat(mismatch.expected()).contains("REVENUE SELLER_BURDEN IN 10500");
            assertThat(mismatch.actual()).contains("REVENUE SELLER_BURDEN IN 9500");
        });
    }

    @Test
    @DisplayName("정산금이 다르면 AMOUNT_MISMATCH에 기대 원장과 실제 원장을 남긴다")
    void amountMismatch() {
        List<DetectedMismatch> result = reconcile(target(SettlementStatus.COMPLETED, "28500", "1500", "0"),
                holding("SETTLEMENT", "OUT", "30000"), revenue("SETTLEMENT_FEE", "IN", "1500"),
                seller("SETTLEMENT", "IN", "28000"));

        assertThat(result).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.settlementId()).isEqualTo(SETTLEMENT_ID);
            assertThat(mismatch.type()).isEqualTo(MismatchType.AMOUNT_MISMATCH);
            assertThat(mismatch.expected()).isEqualTo(
                    "HOLDING SETTLEMENT OUT 30000, MEMBER SETTLEMENT IN 28500, REVENUE SETTLEMENT_FEE IN 1500");
            assertThat(mismatch.actual()).isEqualTo(
                    "HOLDING SETTLEMENT OUT 30000, MEMBER SETTLEMENT IN 28000, REVENUE SETTLEMENT_FEE IN 1500");
        });
    }

    @Test
    @DisplayName("같은 원장이 두 번 있으면(건수 다름) AMOUNT_MISMATCH")
    void duplicatedRow() {
        List<DetectedMismatch> result = reconcile(target(SettlementStatus.COMPLETED, "28500", "1500", "0"),
                holding("SETTLEMENT", "OUT", "30000"), revenue("SETTLEMENT_FEE", "IN", "1500"),
                revenue("SETTLEMENT_FEE", "IN", "1500"), seller("SETTLEMENT", "IN", "28500"));

        assertThat(result).extracting(DetectedMismatch::type).containsExactly(MismatchType.AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("판매자가 아닌 회원 지갑의 원장은 UNEXPECTED_WALLET. walletId만 남기고 memberId는 남기지 않는다")
    void otherMemberWallet() {
        List<DetectedMismatch> result = reconcile(target(SettlementStatus.COMPLETED, "28500", "1500", "0"),
                holding("SETTLEMENT", "OUT", "30000"), revenue("SETTLEMENT_FEE", "IN", "1500"),
                seller("SETTLEMENT", "IN", "28500"),
                new SettlementLedgerEntry(99L, "MEMBER", OTHER_MEMBER_ID, "SETTLEMENT", "IN",
                        new BigDecimal("28500"), RECENT));

        assertThat(result).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.type()).isEqualTo(MismatchType.UNEXPECTED_WALLET);
            assertThat(mismatch.actual()).isEqualTo("walletId=99 MEMBER SETTLEMENT IN 28500");
            assertThat(mismatch.expected() + mismatch.actual()).doesNotContain(String.valueOf(OTHER_MEMBER_ID));
        });
    }

    @Test
    @DisplayName("모르는 지갑 유형의 원장도 UNEXPECTED_WALLET")
    void unknownWalletType() {
        List<DetectedMismatch> result = reconcile(target(SettlementStatus.COMPLETED, "28500", "1500", "0"),
                holding("SETTLEMENT", "OUT", "30000"), revenue("SETTLEMENT_FEE", "IN", "1500"),
                seller("SETTLEMENT", "IN", "28500"),
                new SettlementLedgerEntry(300L, "ESCROW", null, "SETTLEMENT_FEE", "IN", new BigDecimal("1"), RECENT));

        assertThat(result).extracting(DetectedMismatch::type).containsExactly(MismatchType.UNEXPECTED_WALLET);
    }

    @Test
    @DisplayName("COMPLETED인데 원장이 없으면 MISSING_LEDGER")
    void completedWithoutLedger() {
        List<DetectedMismatch> result = reconcile(target(SettlementStatus.COMPLETED, "28500", "1500", "0"));

        assertThat(result).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.type()).isEqualTo(MismatchType.MISSING_LEDGER);
            assertThat(mismatch.actual()).isEqualTo("원장 없음");
        });
    }

    @Test
    @DisplayName("COMPLETED인데 판매자 SETTLEMENT IN만 없으면 MISSING_LEDGER만 남기고 금액은 비교하지 않는다")
    void completedWithoutSellerRow() {
        List<DetectedMismatch> result = reconcile(target(SettlementStatus.COMPLETED, "28500", "1500", "0"),
                holding("SETTLEMENT", "OUT", "30000"), revenue("SETTLEMENT_FEE", "IN", "1500"));

        assertThat(result).extracting(DetectedMismatch::type).containsExactly(MismatchType.MISSING_LEDGER);
    }

    @ParameterizedTest
    @EnumSource(value = SettlementStatus.class, names = {"PENDING", "FAILED"})
    @DisplayName("PENDING, FAILED이고 원장이 하나도 없으면 아직 지급 전이라 불일치 없음")
    void unpaidWithoutLedger(SettlementStatus status) {
        assertThat(reconcile(target(status, "28500", "1500", "0"))).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = SettlementStatus.class, names = {"PENDING", "FAILED"})
    @DisplayName("PENDING, FAILED인데 판매자 SETTLEMENT IN 없이 다른 정산 원장만 있으면 시간과 상관없이 AMOUNT_MISMATCH")
    void unpaidWithPartialLedger(SettlementStatus status) {
        List<DetectedMismatch> result = reconcile(target(status, "28500", "1500", "0"),
                holding("SETTLEMENT", "OUT", "30000"), revenue("SETTLEMENT_FEE", "IN", "1500"));

        assertThat(result).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.type()).isEqualTo(MismatchType.AMOUNT_MISMATCH);
            assertThat(mismatch.expected()).isEqualTo("원장 없음 (지급 전)");
            assertThat(mismatch.actual()).isEqualTo("HOLDING SETTLEMENT OUT 30000, REVENUE SETTLEMENT_FEE IN 1500");
        });
    }

    @ParameterizedTest
    @EnumSource(value = SettlementStatus.class, names = {"PENDING", "FAILED"})
    @DisplayName("PENDING, FAILED인데 지급 원장이 유예 시간 넘게 있으면 UNREFLECTED_STATUS")
    void unreflectedStatus(SettlementStatus status) {
        List<DetectedMismatch> result = reconcilePaidAt(target(status, "28500", "1500", "0"), NOW.minusMinutes(20));

        assertThat(result).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.type()).isEqualTo(MismatchType.UNREFLECTED_STATUS);
            assertThat(mismatch.expected()).isEqualTo("COMPLETED");
            assertThat(mismatch.actual()).startsWith(status.name());
        });
    }

    @ParameterizedTest(name = "지급 뒤 {0}초 → 탐지 {1}")
    @CsvSource({
            "599, false",   // 9분 59초
            "600, true",    // 10분
            "601, true"     // 10분 1초
    })
    @DisplayName("유예 시간 경계: 지급 시각에서 정확히 10분이 지난 때부터 UNREFLECTED_STATUS")
    void unreflectedGraceBoundary(long elapsedSeconds, boolean detected) {
        for (SettlementStatus status : List.of(SettlementStatus.PENDING, SettlementStatus.FAILED)) {
            List<DetectedMismatch> result = reconcilePaidAt(target(status, "28500", "1500", "0"),
                    NOW.minusSeconds(elapsedSeconds));

            assertThat(result).extracting(DetectedMismatch::type)
                    .as("상태 %s", status)
                    .isEqualTo(detected ? List.of(MismatchType.UNREFLECTED_STATUS) : List.of());
        }
    }

    @Test
    @DisplayName("PENDING인데 지급 원장 금액도 다르고 유예 시간도 지났으면 AMOUNT_MISMATCH와 UNREFLECTED_STATUS 둘 다")
    void unpaidWithWrongAmountOverGrace() {
        LocalDateTime paidAt = NOW.minusMinutes(30);
        List<DetectedMismatch> result = reconcile(target(SettlementStatus.PENDING, "28500", "1500", "0"),
                new SettlementLedgerEntry(100L, "HOLDING", null, "SETTLEMENT", "OUT", new BigDecimal("30000"), paidAt),
                new SettlementLedgerEntry(1L, "MEMBER", SELLER_ID, "SETTLEMENT", "IN", new BigDecimal("1"), paidAt));

        assertThat(result).extracting(DetectedMismatch::type)
                .containsExactly(MismatchType.AMOUNT_MISMATCH, MismatchType.UNREFLECTED_STATUS);
    }

    @Test
    @DisplayName("COMPLETED는 지급 원장이 오래돼도 UNREFLECTED_STATUS가 아니다")
    void completedIsNeverUnreflected() {
        assertThat(reconcilePaidAt(target(SettlementStatus.COMPLETED, "28500", "1500", "0"), NOW.minusDays(1)))
                .isEmpty();
    }

    private static List<DetectedMismatch> reconcilePaidAt(ReconciliationTarget target, LocalDateTime paidAt) {
        return SettlementLedgerReconciler.reconcile(target, List.of(
                new SettlementLedgerEntry(100L, "HOLDING", null, "SETTLEMENT", "OUT", new BigDecimal("30000"), paidAt),
                new SettlementLedgerEntry(200L, "REVENUE", null, "SETTLEMENT_FEE", "IN", new BigDecimal("1500"), paidAt),
                new SettlementLedgerEntry(1L, "MEMBER", SELLER_ID, "SETTLEMENT", "IN", new BigDecimal("28500"), paidAt)),
                NOW, GRACE);
    }

    private static List<DetectedMismatch> reconcile(ReconciliationTarget target, SettlementLedgerEntry... rows) {
        return SettlementLedgerReconciler.reconcile(target, List.of(rows), NOW, GRACE);
    }

    private static ReconciliationTarget target(SettlementStatus status, String payout, String fee, String burden) {
        return new ReconciliationTarget(SETTLEMENT_ID, SELLER_ID, status,
                new BigDecimal(fee), new BigDecimal(burden), new BigDecimal(payout));
    }

    private static SettlementLedgerEntry seller(String refType, String status, String amount) {
        return new SettlementLedgerEntry(1L, "MEMBER", SELLER_ID, refType, status, new BigDecimal(amount), RECENT);
    }

    private static SettlementLedgerEntry holding(String refType, String status, String amount) {
        return new SettlementLedgerEntry(100L, "HOLDING", null, refType, status, new BigDecimal(amount), RECENT);
    }

    private static SettlementLedgerEntry revenue(String refType, String status, String amount) {
        return new SettlementLedgerEntry(200L, "REVENUE", null, refType, status, new BigDecimal(amount), RECENT);
    }
}
