package com.justcommit.backend.payment.application;

import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.WalletType;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MemberWalletDepositorTest {

    private static final Long MEMBER_ID = 10L;
    private static final Long MEMBER_WALLET_ID = 1L;
    private static final Long REVENUE_WALLET_ID = 3L;
    private static final Long REF_ID = 500L;
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 11, 19, 12, 0);

    @Mock WalletRepository walletRepository;
    @Mock WalletTransactionRepository walletTransactionRepository;

    private MemberWalletDepositor depositor;
    private final List<WalletTransaction> ledger = new ArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1000);
    private Wallet member;
    private Wallet revenue;

    @BeforeEach
    void setUp() {
        depositor = new MemberWalletDepositor(walletRepository, walletTransactionRepository);
        member = wallet(Wallet.forMember(MEMBER_ID), MEMBER_WALLET_ID);
        revenue = wallet(Wallet.system(WalletType.REVENUE), REVENUE_WALLET_ID);
        revenue.credit(new BigDecimal("1000"));

        lenient().when(walletRepository.findByWalletTypeForUpdate(WalletType.REVENUE)).thenReturn(Optional.of(revenue));
        // IDENTITY처럼 저장할 때 ID를 채운다
        lenient().when(walletTransactionRepository.save(any())).thenAnswer(invocation -> {
            WalletTransaction transaction = invocation.getArgument(0);
            ReflectionTestUtils.setField(transaction, "id", nextId.getAndIncrement());
            ledger.add(transaction);
            return transaction;
        });
    }

    @Test
    @DisplayName("미수금이 0이면 입금 행만 남기고 REVENUE는 조회하지도 잠그지도 않는다")
    void noReceivableDoesNotTouchRevenue() {
        BigDecimal collected = deposit("10000");

        assertThat(collected).isEqualByComparingTo("0");
        assertThat(member.getBalance()).isEqualByComparingTo("10000");
        assertThat(rows()).containsExactly(
                tuple(MEMBER_WALLET_ID, RefType.ORDER_ITEM_CANCEL_REFUND, REF_ID, TransactionStatus.IN, "10000", "10000", "0"));
        verify(walletRepository, never()).findByWalletTypeForUpdate(any());
        verifyNoInteractions(walletRepository);
    }

    @Test
    @DisplayName("미수금 < 입금액: 미수금 전액을 차감해 REVENUE로 옮기고 나머지는 잔액에 남는다")
    void receivableLessThanDeposit() {
        member.increaseReceivable(new BigDecimal("3000"));

        BigDecimal collected = deposit("10000");

        assertThat(collected).isEqualByComparingTo("3000");
        assertThat(member.getBalance()).isEqualByComparingTo("7000");
        assertThat(member.getReceivable()).isEqualByComparingTo("0");
        assertThat(revenue.getBalance()).isEqualByComparingTo("4000");
        Long depositId = ledger.get(0).getId();
        assertThat(rows()).containsExactly(
                tuple(MEMBER_WALLET_ID, RefType.ORDER_ITEM_CANCEL_REFUND, REF_ID, TransactionStatus.IN, "10000", "10000", "3000"),
                tuple(MEMBER_WALLET_ID, RefType.RECEIVABLE_COLLECTION, depositId, TransactionStatus.OUT, "3000", "7000", "0"),
                tuple(REVENUE_WALLET_ID, RefType.RECEIVABLE_COLLECTION, depositId, TransactionStatus.IN, "3000", "4000", "0"));
        assertThat(ledger).allMatch(t -> t.getRefOccurredTime().equals(OCCURRED_AT));
    }

    @ParameterizedTest(name = "미수금 {0}, 입금 {1}")
    @CsvSource({
            "10000, 10000, 0",
            "15000, 10000, 5000",
    })
    @DisplayName("미수금 ≥ 입금액: 입금액 전부를 차감하고 잔액은 0, 남은 미수금은 그대로")
    void receivableAtLeastDeposit(String receivable, String amount, String remaining) {
        member.increaseReceivable(new BigDecimal(receivable));

        BigDecimal collected = deposit(amount);

        assertThat(collected).isEqualByComparingTo(amount);
        assertThat(member.getBalance()).isEqualByComparingTo("0");
        assertThat(member.getReceivable()).isEqualByComparingTo(remaining);
        assertThat(revenue.getBalance()).isEqualByComparingTo(new BigDecimal("1000").add(new BigDecimal(amount)));
        Long depositId = ledger.get(0).getId();
        assertThat(rows()).containsExactly(
                tuple(MEMBER_WALLET_ID, RefType.ORDER_ITEM_CANCEL_REFUND, REF_ID, TransactionStatus.IN, amount, amount, receivable),
                tuple(MEMBER_WALLET_ID, RefType.RECEIVABLE_COLLECTION, depositId, TransactionStatus.OUT, amount, "0", remaining),
                tuple(REVENUE_WALLET_ID, RefType.RECEIVABLE_COLLECTION, depositId, TransactionStatus.IN, amount,
                        new BigDecimal("1000").add(new BigDecimal(amount)).toPlainString(), "0"));
        assertInvariant();
    }

    @Test
    @DisplayName("REVENUE를 넘겨받으면 다시 조회하거나 잠그지 않고 그 지갑에 입금한다")
    void usesGivenLockedRevenue() {
        member.increaseReceivable(new BigDecimal("3000"));

        depositor.deposit(member, new BigDecimal("10000"), this::refundRow, revenue);

        assertThat(revenue.getBalance()).isEqualByComparingTo("4000");
        verifyNoInteractions(walletRepository);
    }

    @Test
    @DisplayName("입금 행 금액이 입금액과 다르면 IllegalStateException")
    void rejectsMismatchedDepositRow() {
        assertThatThrownBy(() -> depositor.deposit(member, new BigDecimal("10000"),
                w -> WalletTransaction.refundIn(w, RefType.ORDER_ITEM_CANCEL_REFUND, REF_ID, new BigDecimal("9000"), OCCURRED_AT)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("시스템 지갑에는 입금할 수 없다")
    void rejectsSystemWallet() {
        assertThatThrownBy(() -> depositor.deposit(revenue, new BigDecimal("10000"), this::refundRow))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private BigDecimal deposit(String amount) {
        BigDecimal value = new BigDecimal(amount);
        return depositor.deposit(member, value,
                w -> WalletTransaction.refundIn(w, RefType.ORDER_ITEM_CANCEL_REFUND, REF_ID, value, OCCURRED_AT));
    }

    private WalletTransaction refundRow(Wallet wallet) {
        return WalletTransaction.refundIn(wallet, RefType.ORDER_ITEM_CANCEL_REFUND, REF_ID, new BigDecimal("10000"), OCCURRED_AT);
    }

    // 처리 후 미수금이 남아 있으면 잔액은 0
    private void assertInvariant() {
        if (member.getReceivable().signum() > 0) {
            assertThat(member.getBalance()).isEqualByComparingTo("0");
        }
    }

    // (지갑 ID, 거래 유형, 대상 ID, 입출금, 금액, 처리 후 잔액, 처리 후 미수금)
    private List<Tuple> rows() {
        return ledger.stream().map(t -> tuple(t.getWallet().getId(), t.getRefType(), t.getRefId(), t.getStatus(),
                t.getAmount().toPlainString(), t.getBalanceAfter().toPlainString(),
                t.getReceivableAfter().toPlainString())).toList();
    }

    private static Wallet wallet(Wallet wallet, Long walletId) {
        ReflectionTestUtils.setField(wallet, "id", walletId);
        return wallet;
    }
}
