package com.justcommit.backend.settlement.application.reconciliation;

import com.justcommit.backend.settlement.client.payment.SettlementLedgerEntry;
import com.justcommit.backend.settlement.domain.SettlementStatus;
import com.justcommit.backend.settlement.domain.reconciliation.MismatchType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

// 정산 하나의 정산 원장을 기대 원장과 비교한다 (docs/flows/confirm-settlement.md 「정산 대사」).
// 기대 원장은 payment의 정산 지급(distributeSettlement)이 쓰는 행과 같다:
//   HOLDING  SETTLEMENT            OUT 판매액(= 정산금 + 수수료 + 판매자 부담금), 0이면 없음
//   REVENUE  SETTLEMENT_FEE        IN  수수료, 0이면 없음
//   판매자    SETTLEMENT            IN  정산금, 정산금이 0 이하면 0원 1건 (지급 여부의 기준 행)
//   판매자    SETTLEMENT_COLLECTION OUT 회수액 c (음수 정산이고 c > 0일 때만)
//   REVENUE  SELLER_BURDEN         IN  판매자 부담금 - (|정산금| - c), 0 이하면 없음 (음수 정산이 아니면 c = |정산금| 취급)
// payment는 한 정산의 원장을 한 트랜잭션에서 모두 쓰므로, 보이는 원장은 이미 완성된 것으로 보고 상태와 상관없이 금액을 비교한다.
// 유예 시간은 정산 상태 반영(UNREFLECTED_STATUS)에만 적용한다
public final class SettlementLedgerReconciler {

    static final String MEMBER = "MEMBER";
    static final String HOLDING = "HOLDING";
    static final String REVENUE = "REVENUE";
    static final String SETTLEMENT = "SETTLEMENT";
    static final String SETTLEMENT_FEE = "SETTLEMENT_FEE";
    static final String SETTLEMENT_COLLECTION = "SETTLEMENT_COLLECTION";
    static final String SELLER_BURDEN = "SELLER_BURDEN";
    static final String IN = "IN";
    static final String OUT = "OUT";

    static final String NO_LEDGER = "원장 없음";

    private SettlementLedgerReconciler() {
    }

    public static List<DetectedMismatch> reconcile(ReconciliationTarget target, List<SettlementLedgerEntry> rows,
                                                   LocalDateTime now, Duration unreflectedGrace) {
        List<SettlementLedgerEntry> ledger = rows == null ? List.of() : rows;
        Long settlementId = target.settlementId();
        List<DetectedMismatch> mismatches = new ArrayList<>();

        List<SettlementLedgerEntry> known = new ArrayList<>();
        List<SettlementLedgerEntry> unexpected = new ArrayList<>();
        for (SettlementLedgerEntry row : ledger) {
            (isExpectedWallet(row, target.sellerId()) ? known : unexpected).add(row);
        }
        if (!unexpected.isEmpty()) {
            mismatches.add(new DetectedMismatch(settlementId, MismatchType.UNEXPECTED_WALLET,
                    "판매자 회원 지갑, HOLDING, REVENUE", describeWithWallet(unexpected)));
        }

        List<SettlementLedgerEntry> sellerPayouts = known.stream()
                .filter(row -> matches(row, MEMBER, SETTLEMENT, IN))
                .toList();

        if (sellerPayouts.isEmpty()) {
            if (target.status() == SettlementStatus.COMPLETED) {
                // 지급 완료로 기록했는데 payment에는 지급 기준 행이 없다. 금액 비교는 의미가 없어 하지 않는다
                mismatches.add(new DetectedMismatch(settlementId, MismatchType.MISSING_LEDGER,
                        "판매자 SETTLEMENT IN 1건", describe(known)));
            } else if (!known.isEmpty()) {
                // 지급 기준 행 없이 다른 정산 원장만 있다. 한 트랜잭션으로 쓰므로 시간이 지나도 채워지지 않는다
                mismatches.add(new DetectedMismatch(settlementId, MismatchType.AMOUNT_MISMATCH,
                        NO_LEDGER + " (지급 전)", describe(known)));
            }
            return mismatches;
        }

        compareAmounts(target, known).ifPresent(mismatches::add);

        if (target.status() != SettlementStatus.COMPLETED) {
            sellerPayouts.stream()
                    .map(SettlementLedgerEntry::occurredAt)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .filter(paidAt -> isGraceOver(paidAt, now, unreflectedGrace))
                    .ifPresent(paidAt -> mismatches.add(new DetectedMismatch(settlementId,
                            MismatchType.UNREFLECTED_STATUS, SettlementStatus.COMPLETED.name(),
                            target.status().name() + ", payment 지급 시각 " + paidAt)));
        }
        return mismatches;
    }

    // payment 지급 시각에서 유예 시간이 지났는지. 정확히 유예 시간만큼 지난 때부터 탐지한다
    static boolean isGraceOver(LocalDateTime paidAt, LocalDateTime now, Duration unreflectedGrace) {
        return !paidAt.plus(unreflectedGrace).isAfter(now);
    }

    private static Optional<DetectedMismatch> compareAmounts(ReconciliationTarget target,
                                                             List<SettlementLedgerEntry> known) {
        BigDecimal payout = target.payoutAmount();
        BigDecimal fee = target.fee();
        BigDecimal burden = target.sellerBurdenAmount();
        BigDecimal saleAmount = payout.add(fee).add(burden);

        List<String> expected = new ArrayList<>();
        if (saleAmount.signum() > 0) {
            expected.add(line(HOLDING, SETTLEMENT, OUT, saleAmount));
        }
        if (fee.signum() > 0) {
            expected.add(line(REVENUE, SETTLEMENT_FEE, IN, fee));
        }

        if (payout.signum() > 0) {
            expected.add(line(MEMBER, SETTLEMENT, IN, payout));
            addIfPositive(expected, line(REVENUE, SELLER_BURDEN, IN, burden), burden);
        } else {
            expected.add(line(MEMBER, SETTLEMENT, IN, BigDecimal.ZERO));
            BigDecimal deficit = payout.negate();
            BigDecimal collected = BigDecimal.ZERO;
            if (deficit.signum() > 0) {
                // 회수액은 지급 시점의 판매자 잔액으로 payment가 정한다. settlement는 그 잔액을 모르므로
                // 0 < c <= |정산금|인지만 보고, 그 c로 판매자 부담금 IN을 맞춘다 (회수액 자체는 검증하지 못함)
                List<SettlementLedgerEntry> collections = known.stream()
                        .filter(row -> matches(row, MEMBER, SETTLEMENT_COLLECTION, OUT))
                        .toList();
                if (collections.size() > 1 || (collections.size() == 1
                        && !isValidCollection(collections.getFirst().amount(), deficit))) {
                    return Optional.of(new DetectedMismatch(target.settlementId(), MismatchType.AMOUNT_MISMATCH,
                            "판매자 SETTLEMENT_COLLECTION OUT 0건 또는 1건 (0 < 금액 <= " + format(deficit) + ")",
                            describe(known)));
                }
                if (collections.size() == 1) {
                    collected = collections.getFirst().amount();
                    expected.add(line(MEMBER, SETTLEMENT_COLLECTION, OUT, collected));
                }
            }
            BigDecimal receivedBurden = burden.subtract(deficit).add(collected);
            addIfPositive(expected, line(REVENUE, SELLER_BURDEN, IN, receivedBurden), receivedBurden);
        }

        List<String> actual = lines(known);
        expected.sort(Comparator.naturalOrder());
        if (expected.equals(actual)) {
            return Optional.empty();
        }
        return Optional.of(new DetectedMismatch(target.settlementId(), MismatchType.AMOUNT_MISMATCH,
                String.join(", ", expected), describe(known)));
    }

    private static boolean isValidCollection(BigDecimal collected, BigDecimal deficit) {
        return collected != null && collected.signum() > 0 && collected.compareTo(deficit) <= 0;
    }

    private static void addIfPositive(List<String> lines, String line, BigDecimal amount) {
        if (amount.signum() > 0) {
            lines.add(line);
        }
    }

    private static boolean isExpectedWallet(SettlementLedgerEntry row, Long sellerId) {
        if (HOLDING.equals(row.walletType()) || REVENUE.equals(row.walletType())) {
            return true;
        }
        return MEMBER.equals(row.walletType()) && Objects.equals(row.memberId(), sellerId);
    }

    private static boolean matches(SettlementLedgerEntry row, String walletType, String refType, String status) {
        return walletType.equals(row.walletType()) && refType.equals(row.refType()) && status.equals(row.status());
    }

    private static List<String> lines(List<SettlementLedgerEntry> rows) {
        return rows.stream()
                .map(row -> line(row.walletType(), row.refType(), row.status(), row.amount()))
                .sorted()
                .toList();
    }

    private static String describe(List<SettlementLedgerEntry> rows) {
        return rows.isEmpty() ? NO_LEDGER : String.join(", ", lines(rows));
    }

    // 예상하지 못한 지갑은 walletId로 남긴다 (memberId는 개인 식별 정보라 기록 · 로그에 넣지 않음)
    private static String describeWithWallet(List<SettlementLedgerEntry> rows) {
        return String.join(", ", rows.stream()
                .map(row -> "walletId=" + row.walletId() + " "
                        + line(row.walletType(), row.refType(), row.status(), row.amount()))
                .sorted()
                .toList());
    }

    // 지갑 유형은 판매자 회원 지갑도 MEMBER로 쓴다 (판매자가 아닌 회원 지갑은 UNEXPECTED_WALLET으로 따로 나감)
    private static String line(String walletType, String refType, String status, BigDecimal amount) {
        return walletType + " " + refType + " " + status + " " + format(amount);
    }

    private static String format(BigDecimal amount) {
        return amount == null ? "null" : amount.stripTrailingZeros().toPlainString();
    }
}
