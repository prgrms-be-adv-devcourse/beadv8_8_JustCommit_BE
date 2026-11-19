package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.WalletType;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// 정산 지급의 돈 흐름. 거래 내역 저장소는 메모리 원장으로 흉내 내서 중복 판정(exists)이 저장한 행을 그대로 본다
@ExtendWith(MockitoExtension.class)
class PaymentServiceSettlementTest {

    private static final Long SETTLEMENT_ID = 100L;
    private static final Long SELLER_ID = 4L;
    private static final Long SELLER_ORDER_ID = 200L;

    private static final Long SELLER_WALLET_ID = 1L;
    private static final Long HOLDING_WALLET_ID = 2L;
    private static final Long REVENUE_WALLET_ID = 3L;

    @Mock WalletRepository walletRepository;
    @Mock WalletTransactionRepository walletTransactionRepository;
    @Mock WalletService walletService;
    PaymentService paymentService;

    // 입금은 진짜 MemberWalletDepositor로 (목 저장소 사용) 해야 잔액, 원장을 확인할 수 있다
    @BeforeEach
    void createPaymentService() {
        paymentService = new PaymentService(walletRepository, walletTransactionRepository, walletService,
                new MemberWalletDepositor(walletRepository, walletTransactionRepository));
    }

    private final List<WalletTransaction> ledger = new ArrayList<>();
    private Wallet seller;
    private Wallet holding;
    private Wallet revenue;

    @BeforeEach
    void setUp() {
        seller = wallet(Wallet.forMember(SELLER_ID), SELLER_WALLET_ID, "0");
        holding = wallet(Wallet.system(WalletType.HOLDING), HOLDING_WALLET_ID, "100000");
        revenue = wallet(Wallet.system(WalletType.REVENUE), REVENUE_WALLET_ID, "0");

        lenient().when(walletRepository.findByMemberIdForUpdate(SELLER_ID)).thenReturn(Optional.of(seller));
        lenient().when(walletRepository.findByWalletTypeForUpdate(WalletType.HOLDING)).thenReturn(Optional.of(holding));
        lenient().when(walletRepository.findByWalletTypeForUpdate(WalletType.REVENUE)).thenReturn(Optional.of(revenue));
        lenient().when(walletTransactionRepository.save(any())).thenAnswer(invocation -> {
            WalletTransaction transaction = invocation.getArgument(0);
            // IDENTITY처럼 저장할 때 ID를 채운다 (미수금 차감 행의 ref_id = 입금 행 ID)
            ReflectionTestUtils.setField(transaction, "id", (long) ledger.size() + 1000);
            ledger.add(transaction);
            return transaction;
        });
        lenient().when(walletTransactionRepository.existsByWallet_IdAndRefTypeAndRefId(anyLong(), any(), anyLong()))
                .thenAnswer(invocation -> ledger.stream().anyMatch(t ->
                        t.getWallet().getId().equals(invocation.getArgument(0))
                                && t.getRefType() == invocation.getArgument(1)
                                && t.getRefId().equals(invocation.getArgument(2))));
    }

    @Test
    @DisplayName("부담금 · 배송비가 없으면 기존과 같이 HOLDING에서 정산금 + 수수료를 꺼내 판매자와 REVENUE로 나눈다")
    void positiveWithoutBurdenKeepsPreviousResult() {
        distribute(null, "9500", "500", "0", "0");

        assertThat(seller.getBalance()).isEqualByComparingTo("9500");
        assertThat(holding.getBalance()).isEqualByComparingTo("90000");
        assertThat(revenue.getBalance()).isEqualByComparingTo("500");
        assertThat(seller.getReceivable()).isEqualByComparingTo("0");
        assertThat(rows())
                .containsExactly(
                        tuple(HOLDING_WALLET_ID, RefType.SETTLEMENT, SETTLEMENT_ID, TransactionStatus.OUT, "10000", "90000", "0"),
                        tuple(REVENUE_WALLET_ID, RefType.SETTLEMENT_FEE, SETTLEMENT_ID, TransactionStatus.IN, "500", "500", "0"),
                        tuple(SELLER_WALLET_ID, RefType.SETTLEMENT, SETTLEMENT_ID, TransactionStatus.IN, "9500", "9500", "0"));
        assertBalanced();
    }

    @Test
    @DisplayName("부담금 · 배송비가 있으면 HOLDING에서 판매액 + 배송비를 꺼내 수수료 · 배송비 · 부담금을 REVENUE로 옮긴다")
    void positiveWithBurdenAndShippingFee() {
        // 판매액 30000 = 정산금 22500 + 수수료 1500 + 부담금 6000
        distribute(SELLER_ORDER_ID, "22500", "1500", "3000", "6000");

        assertThat(seller.getBalance()).isEqualByComparingTo("22500");
        assertThat(holding.getBalance()).isEqualByComparingTo("67000");
        assertThat(revenue.getBalance()).isEqualByComparingTo("10500");
        assertThat(rows())
                .containsExactly(
                        tuple(HOLDING_WALLET_ID, RefType.SETTLEMENT, SETTLEMENT_ID, TransactionStatus.OUT, "30000", "70000", "0"),
                        tuple(REVENUE_WALLET_ID, RefType.SETTLEMENT_FEE, SETTLEMENT_ID, TransactionStatus.IN, "1500", "1500", "0"),
                        tuple(HOLDING_WALLET_ID, RefType.SHIPPING_FEE, SELLER_ORDER_ID, TransactionStatus.OUT, "3000", "67000", "0"),
                        tuple(REVENUE_WALLET_ID, RefType.SHIPPING_FEE, SELLER_ORDER_ID, TransactionStatus.IN, "3000", "4500", "0"),
                        tuple(SELLER_WALLET_ID, RefType.SETTLEMENT, SETTLEMENT_ID, TransactionStatus.IN, "22500", "22500", "0"),
                        tuple(REVENUE_WALLET_ID, RefType.SELLER_BURDEN, SETTLEMENT_ID, TransactionStatus.IN, "6000", "10500", "0"));
        assertBalanced();
    }

    @Test
    @DisplayName("0원 정산은 판매자 잔액을 바꾸지 않고 SETTLEMENT 0원 행만 남기며, 부담금은 전액 REVENUE로 간다")
    void zeroPayout() {
        credit(seller, "5000");

        distribute(null, "0", "1500", "0", "28500");

        assertThat(seller.getBalance()).isEqualByComparingTo("5000");
        assertThat(seller.getReceivable()).isEqualByComparingTo("0");
        assertThat(holding.getBalance()).isEqualByComparingTo("70000");
        assertThat(revenue.getBalance()).isEqualByComparingTo("30000");
        assertThat(rowsOf(SELLER_WALLET_ID))
                .containsExactly(tuple(SELLER_WALLET_ID, RefType.SETTLEMENT, SETTLEMENT_ID, TransactionStatus.IN, "0", "5000", "0"));
        assertBalanced();
    }

    @Test
    @DisplayName("마이너스 정산은 판매자 잔액이 충분하면 전액 회수해 REVENUE로 보내고 미수금은 늘지 않는다")
    void negativeFullyCollected() {
        credit(seller, "10000");

        // 판매액 11000 = 정산금 -2500 + 수수료 500 + 부담금 13000
        distribute(null, "-2500", "500", "0", "13000");

        assertThat(seller.getBalance()).isEqualByComparingTo("7500");
        assertThat(seller.getReceivable()).isEqualByComparingTo("0");
        assertThat(holding.getBalance()).isEqualByComparingTo("89000");
        assertThat(revenue.getBalance()).isEqualByComparingTo("13500");
        assertThat(rowsOf(SELLER_WALLET_ID))
                .containsExactly(
                        tuple(SELLER_WALLET_ID, RefType.SETTLEMENT_COLLECTION, SETTLEMENT_ID, TransactionStatus.OUT, "2500", "7500", "0"),
                        tuple(SELLER_WALLET_ID, RefType.SETTLEMENT, SETTLEMENT_ID, TransactionStatus.IN, "0", "7500", "0"));
        assertThat(rowsOf(REVENUE_WALLET_ID))
                .extracting(r -> r.toList().get(1), r -> r.toList().get(4))
                .containsExactly(tuple(RefType.SETTLEMENT_FEE, "500"), tuple(RefType.SELLER_BURDEN, "13000"));
        assertBalanced();
    }

    @Test
    @DisplayName("마이너스 정산을 잔액으로 일부만 회수하면 나머지는 미수금이 되고, REVENUE 부담금은 받은 만큼만 들어간다")
    void negativePartiallyCollected() {
        credit(seller, "1000");

        distribute(null, "-2500", "500", "0", "13000");

        assertThat(seller.getBalance()).isEqualByComparingTo("0");
        assertThat(seller.getReceivable()).isEqualByComparingTo("1500");
        assertThat(holding.getBalance()).isEqualByComparingTo("89000");
        assertThat(revenue.getBalance()).isEqualByComparingTo("12000");
        // 판매자 행의 balance_after · receivable_after는 이 정산의 판매자 지갑 변경이 모두 끝난 최종 값
        assertThat(rowsOf(SELLER_WALLET_ID))
                .containsExactly(
                        tuple(SELLER_WALLET_ID, RefType.SETTLEMENT_COLLECTION, SETTLEMENT_ID, TransactionStatus.OUT, "1000", "0", "1500"),
                        tuple(SELLER_WALLET_ID, RefType.SETTLEMENT, SETTLEMENT_ID, TransactionStatus.IN, "0", "0", "1500"));
        assertThat(rowsOf(REVENUE_WALLET_ID))
                .extracting(r -> r.toList().get(1), r -> r.toList().get(4), r -> r.toList().get(6))
                .containsExactly(tuple(RefType.SETTLEMENT_FEE, "500", "0"), tuple(RefType.SELLER_BURDEN, "11500", "0"));
        assertBalanced();
        assertReceivableIncrease("1500", "13000", "11500");
    }

    @Test
    @DisplayName("판매자 잔액이 0이면 마이너스 정산 전액이 미수금이 되고 회수 출금 행은 남기지 않는다")
    void negativeWithEmptyBalanceBecomesReceivable() {
        distribute(null, "-2500", "500", "0", "13000");

        assertThat(seller.getBalance()).isEqualByComparingTo("0");
        assertThat(seller.getReceivable()).isEqualByComparingTo("2500");
        assertThat(revenue.getBalance()).isEqualByComparingTo("11000");
        assertThat(rowsOf(SELLER_WALLET_ID))
                .containsExactly(tuple(SELLER_WALLET_ID, RefType.SETTLEMENT, SETTLEMENT_ID, TransactionStatus.IN, "0", "0", "2500"));
        assertBalanced();
        assertReceivableIncrease("2500", "13000", "10500");
    }

    @Test
    @DisplayName("판매액 0(전부 반품)이면 HOLDING에서 판매액을 꺼내지 않고 부담금은 판매자에게서만 받는다")
    void zeroSaleAmountMovesNothingFromHolding() {
        credit(seller, "2000");

        distribute(null, "-6000", "0", "0", "6000");

        assertThat(holding.getBalance()).isEqualByComparingTo("100000");
        assertThat(seller.getBalance()).isEqualByComparingTo("0");
        assertThat(seller.getReceivable()).isEqualByComparingTo("4000");
        assertThat(revenue.getBalance()).isEqualByComparingTo("2000");
        assertThat(rowsOf(HOLDING_WALLET_ID)).isEmpty();
        assertThat(rowsOf(REVENUE_WALLET_ID))
                .extracting(r -> r.toList().get(1), r -> r.toList().get(4))
                .containsExactly(tuple(RefType.SELLER_BURDEN, "2000"));
        assertBalanced();
        assertReceivableIncrease("4000", "6000", "2000");
    }

    @Test
    @DisplayName("이미 구매자에게 환불된 배송비는 옮기지 않고 정산 지급은 계속한다")
    void skipsRefundedShippingFee() {
        ledger.add(WalletTransaction.refundOut(holding, RefType.SHIPPING_FEE_REFUND, SELLER_ORDER_ID,
                new BigDecimal("3000"), LocalDateTime.now()));
        int before = ledger.size();

        distribute(SELLER_ORDER_ID, "-2500", "500", "3000", "13000");

        assertThat(ledger.subList(before, ledger.size()))
                .noneMatch(t -> t.getRefType() == RefType.SHIPPING_FEE);
        assertThat(holding.getBalance()).isEqualByComparingTo("89000");
        assertThat(seller.getReceivable()).isEqualByComparingTo("2500");
    }

    @Test
    @DisplayName("배송비가 이미 옮겨졌으면 다시 옮기지 않는다")
    void doesNotMoveShippingFeeTwice() {
        paymentService.collectShippingFee(SELLER_ORDER_ID, new BigDecimal("3000"));
        assertThat(holding.getBalance()).isEqualByComparingTo("97000");

        distribute(SELLER_ORDER_ID, "9500", "500", "3000", "0");

        assertThat(holding.getBalance()).isEqualByComparingTo("87000");
        assertThat(revenue.getBalance()).isEqualByComparingTo("3500");
        assertThat(ledger).filteredOn(t -> t.getRefType() == RefType.SHIPPING_FEE).hasSize(2);
    }

    @ParameterizedTest(name = "정산금 {0}, 잔액 {1}")
    @CsvSource({
            "9500,  0",       // 양수
            "0,     0",       // 0원
            "-2500, 1000"     // 마이너스 (일부 회수 + 미수금)
    })
    @DisplayName("같은 settlementId로 다시 오면 돈 이동 · 미수금 · 배송비 이동이 한 번만 일어난다")
    void sameSettlementIsProcessedOnce(String payoutAmount, String sellerBalance) {
        credit(seller, sellerBalance);
        String burden = new BigDecimal(payoutAmount).signum() > 0 ? "0" : "13000";

        distribute(SELLER_ORDER_ID, payoutAmount, "500", "3000", burden);
        int rowCount = ledger.size();
        BigDecimal sellerBalanceAfter = seller.getBalance();
        BigDecimal receivableAfter = seller.getReceivable();
        BigDecimal holdingAfter = holding.getBalance();
        BigDecimal revenueAfter = revenue.getBalance();

        distribute(SELLER_ORDER_ID, payoutAmount, "500", "3000", burden);

        assertThat(ledger).hasSize(rowCount);
        assertThat(seller.getBalance()).isEqualByComparingTo(sellerBalanceAfter);
        assertThat(seller.getReceivable()).isEqualByComparingTo(receivableAfter);
        assertThat(holding.getBalance()).isEqualByComparingTo(holdingAfter);
        assertThat(revenue.getBalance()).isEqualByComparingTo(revenueAfter);
    }

    @ParameterizedTest(name = "[{index}] {6}")
    @CsvSource(nullValues = "null", value = {
            "null, 4,    9500,  500, 0,    0,    settlementId 없음",
            "100,  null, 9500,  500, 0,    0,    sellerId 없음",
            "100,  4,    null,  500, 0,    0,    정산금 없음",
            "100,  4,    9500,  null, 0,   0,    수수료 없음",
            "100,  4,    9500,  500, null, 0,    배송비 없음",
            "100,  4,    9500,  500, 0,    null, 부담금 없음",
            "100,  4,    9500,  -1,  0,    0,    수수료 음수",
            "100,  4,    9500,  500, 0,    -1,   부담금 음수",
            "100,  4,    9500,  500, -1,   0,    배송비 음수",
            "100,  4,    -1001, 0,   0,    1000, 판매액 음수",
            "100,  4,    -1001, 500, 0,    1000, 수수료가 판매액보다 큼",
    })
    @DisplayName("요청 값이 잘못되면 INVALID_AMOUNT로 거절하고 지갑을 건드리지 않는다")
    void rejectsInvalidRequest(Long settlementId, Long sellerId, BigDecimal payoutAmount, BigDecimal fee,
                               BigDecimal shippingFee, BigDecimal sellerBurdenAmount, String reason) {
        assertThatThrownBy(() -> paymentService.distributeSettlement(
                settlementId, sellerId, SELLER_ORDER_ID, payoutAmount, fee, shippingFee, sellerBurdenAmount))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_AMOUNT);

        verifyNoInteractions(walletRepository, walletTransactionRepository);
    }

    @Test
    @DisplayName("배송비가 있는데 sellerOrderId가 없으면 INVALID_AMOUNT")
    void rejectsShippingFeeWithoutSellerOrderId() {
        assertThatThrownBy(() -> distribute(null, "9500", "500", "3000", "0"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_AMOUNT);

        verifyNoInteractions(walletRepository, walletTransactionRepository);
    }

    // ===== 미수금 차감 (#380) =====

    @Test
    @DisplayName("[S-G05] 양수 정산, 미수금 < 정산금: 정산금에서 미수금을 먼저 빼 이미 잠근 REVENUE로 옮긴다")
    void positivePayoutCollectsReceivableFirst() {
        seller.increaseReceivable(new BigDecimal("3000"));

        distribute(null, "9500", "500", "0", "0");

        assertThat(seller.getBalance()).isEqualByComparingTo("6500");
        assertThat(seller.getReceivable()).isEqualByComparingTo("0");
        assertThat(revenue.getBalance()).isEqualByComparingTo("3500");
        Long depositId = settlementRowId();
        assertThat(rows())
                .containsExactly(
                        tuple(HOLDING_WALLET_ID, RefType.SETTLEMENT, SETTLEMENT_ID, TransactionStatus.OUT, "10000", "90000", "0"),
                        tuple(REVENUE_WALLET_ID, RefType.SETTLEMENT_FEE, SETTLEMENT_ID, TransactionStatus.IN, "500", "500", "0"),
                        tuple(SELLER_WALLET_ID, RefType.SETTLEMENT, SETTLEMENT_ID, TransactionStatus.IN, "9500", "9500", "3000"),
                        tuple(SELLER_WALLET_ID, RefType.RECEIVABLE_COLLECTION, depositId, TransactionStatus.OUT, "3000", "6500", "0"),
                        tuple(REVENUE_WALLET_ID, RefType.RECEIVABLE_COLLECTION, depositId, TransactionStatus.IN, "3000", "3500", "0"));
        // REVENUE는 처음 한 번만 잠근다
        verify(walletRepository, times(1)).findByWalletTypeForUpdate(WalletType.REVENUE);
        assertBalanced();
    }

    @ParameterizedTest(name = "미수금 {0}")
    @CsvSource({"9500, 0", "12000, 2500"})
    @DisplayName("[S-G05] 양수 정산, 미수금 ≥ 정산금: 정산금 전부를 차감해 판매자 잔액은 0")
    void positivePayoutFullyCollected(String receivable, String remaining) {
        seller.increaseReceivable(new BigDecimal(receivable));

        distribute(null, "9500", "500", "0", "0");

        assertThat(seller.getBalance()).isEqualByComparingTo("0");
        assertThat(seller.getReceivable()).isEqualByComparingTo(remaining);
        assertThat(revenue.getBalance()).isEqualByComparingTo("10000");
        assertThat(sum(SELLER_WALLET_ID, RefType.RECEIVABLE_COLLECTION, TransactionStatus.OUT)).isEqualByComparingTo("9500");
        assertBalanced();
    }

    @Test
    @DisplayName("같은 settlementId로 다시 보내도 미수금 차감은 한 번만")
    void positivePayoutRetryCollectsOnce() {
        seller.increaseReceivable(new BigDecimal("3000"));

        distribute(null, "9500", "500", "0", "0");
        distribute(null, "9500", "500", "0", "0");

        assertThat(seller.getBalance()).isEqualByComparingTo("6500");
        assertThat(revenue.getBalance()).isEqualByComparingTo("3500");
        assertThat(rowsOf(SELLER_WALLET_ID)).hasSize(2);
        assertThat(sum(REVENUE_WALLET_ID, RefType.RECEIVABLE_COLLECTION, TransactionStatus.IN)).isEqualByComparingTo("3000");
    }

    @ParameterizedTest(name = "정산금 {0}")
    @CsvSource({"0, 0, 1500", "-1000, 0, 2500"})
    @DisplayName("0원, 음수 정산에는 미수금 차감이 없다 (#379 흐름 그대로)")
    void nonPositivePayoutDoesNotCollectReceivable(String payout, String fee, String burden) {
        seller.increaseReceivable(new BigDecimal("3000"));

        distribute(null, payout, fee, "0", burden);

        assertThat(sum(SELLER_WALLET_ID, RefType.RECEIVABLE_COLLECTION, TransactionStatus.OUT)).isEqualByComparingTo("0");
        assertThat(sum(REVENUE_WALLET_ID, RefType.RECEIVABLE_COLLECTION, TransactionStatus.IN)).isEqualByComparingTo("0");
        assertBalanced();
    }

    private Long settlementRowId() {
        return ledger.stream()
                .filter(t -> t.getWallet().getId().equals(SELLER_WALLET_ID) && t.getRefType() == RefType.SETTLEMENT)
                .findFirst().orElseThrow().getId();
    }

    private void distribute(Long sellerOrderId, String payoutAmount, String fee, String shippingFee, String burden) {
        paymentService.distributeSettlement(SETTLEMENT_ID, SELLER_ID, sellerOrderId,
                new BigDecimal(payoutAmount), new BigDecimal(fee), new BigDecimal(shippingFee), new BigDecimal(burden));
    }

    // HOLDING 차감(판매액 + 옮긴 배송비) + 판매자에게서 회수, 미수금 차감한 금액 = REVENUE 입금 + 판매자 입금
    private void assertBalanced() {
        BigDecimal holdingOut = sum(HOLDING_WALLET_ID, null, TransactionStatus.OUT);
        BigDecimal collected = sum(SELLER_WALLET_ID, RefType.SETTLEMENT_COLLECTION, TransactionStatus.OUT)
                .add(sum(SELLER_WALLET_ID, RefType.RECEIVABLE_COLLECTION, TransactionStatus.OUT));
        BigDecimal revenueIn = sum(REVENUE_WALLET_ID, null, TransactionStatus.IN);
        BigDecimal sellerIn = sum(SELLER_WALLET_ID, RefType.SETTLEMENT, TransactionStatus.IN);

        assertThat(holdingOut.add(collected)).isEqualByComparingTo(revenueIn.add(sellerIn));
    }

    // 미수금 증가분 = 부족분, 부담금 - 미수금 증가분 = REVENUE 부담금 입금
    private void assertReceivableIncrease(String expectedIncrease, String burden, String expectedBurdenIn) {
        assertThat(seller.getReceivable()).isEqualByComparingTo(expectedIncrease);
        BigDecimal burdenIn = sum(REVENUE_WALLET_ID, RefType.SELLER_BURDEN, TransactionStatus.IN);
        assertThat(burdenIn).isEqualByComparingTo(expectedBurdenIn);
        assertThat(new BigDecimal(burden).subtract(seller.getReceivable())).isEqualByComparingTo(burdenIn);
    }

    private BigDecimal sum(Long walletId, RefType refType, TransactionStatus status) {
        return ledger.stream()
                .filter(t -> t.getWallet().getId().equals(walletId))
                .filter(t -> refType == null || t.getRefType() == refType)
                .filter(t -> t.getStatus() == status)
                .map(WalletTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<Tuple> rows() {
        return ledger.stream().map(PaymentServiceSettlementTest::row).toList();
    }

    private List<Tuple> rowsOf(Long walletId) {
        return ledger.stream().filter(t -> t.getWallet().getId().equals(walletId))
                .map(PaymentServiceSettlementTest::row).toList();
    }

    // (지갑 ID, 거래 유형, 대상 ID, 입출금, 금액, 처리 후 잔액, 처리 후 미수금)
    private static Tuple row(WalletTransaction t) {
        return tuple(t.getWallet().getId(), t.getRefType(), t.getRefId(), t.getStatus(),
                t.getAmount().toPlainString(), t.getBalanceAfter().toPlainString(), t.getReceivableAfter().toPlainString());
    }

    private static void credit(Wallet wallet, String amount) {
        if (new BigDecimal(amount).signum() > 0) {
            wallet.credit(new BigDecimal(amount));
        }
    }

    private static Wallet wallet(Wallet wallet, Long walletId, String balance) {
        ReflectionTestUtils.setField(wallet, "id", walletId);
        credit(wallet, balance);
        return wallet;
    }
}
