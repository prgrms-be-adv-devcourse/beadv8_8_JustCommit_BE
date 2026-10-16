package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.MemberQuery;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceShippingFeeTest {

    private static final Long SELLER_ORDER_ID = 200L;

    private static final Long HOLDING_ID = 999L;
    private static final Long HOLDING_WALLET_ID = 2L;

    private static final Long REVENUE_ID = 998L;
    private static final Long REVENUE_WALLET_ID = 3L;

    @Mock WalletRepository walletRepository;
    @Mock WalletTransactionRepository walletTransactionRepository;
    @Mock MemberQuery memberQuery;
    @InjectMocks PaymentService paymentService;

    @Test
    @DisplayName("수거된 판매자 주문의 배송비를 HOLDING에서 REVENUE로 옮긴다")
    void movesShippingFeeFromHoldingToRevenue() {
        Wallet holding = wallet(HOLDING_ID, HOLDING_WALLET_ID, "10000");
        Wallet revenue = wallet(REVENUE_ID, REVENUE_WALLET_ID, "0");
        stubHolding(holding);
        stubRevenue(revenue);
        stubHoldingHas(RefType.SHIPPING_FEE, false);
        stubHoldingHas(RefType.SHIPPING_FEE_REFUND, false);

        paymentService.collectShippingFee(SELLER_ORDER_ID, new BigDecimal("3000"));

        assertThat(holding.getBalance()).isEqualByComparingTo("7000");
        assertThat(revenue.getBalance()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("HOLDING 출금과 REVENUE 입금 내역이 판매자 주문 ID와 SHIPPING_FEE로 기록된다")
    void recordsHoldingOutAndRevenueIn() {
        stubHolding(wallet(HOLDING_ID, HOLDING_WALLET_ID, "10000"));
        stubRevenue(wallet(REVENUE_ID, REVENUE_WALLET_ID, "0"));
        stubHoldingHas(RefType.SHIPPING_FEE, false);
        stubHoldingHas(RefType.SHIPPING_FEE_REFUND, false);

        paymentService.collectShippingFee(SELLER_ORDER_ID, new BigDecimal("3000"));

        List<WalletTransaction> saved = capturedTransactions(2);
        assertThat(saved)
                .extracting(t -> t.getWallet().getId(), WalletTransaction::getRefType, WalletTransaction::getRefId,
                        WalletTransaction::getStatus, t -> t.getAmount().toPlainString(),
                        t -> t.getBalanceAfter().toPlainString())
                .containsExactly(
                        tuple(HOLDING_WALLET_ID, RefType.SHIPPING_FEE, SELLER_ORDER_ID,
                                TransactionStatus.OUT, "3000", "7000"),
                        tuple(REVENUE_WALLET_ID, RefType.SHIPPING_FEE, SELLER_ORDER_ID,
                                TransactionStatus.IN, "3000", "3000"));
    }

    @Test
    @DisplayName("같은 판매자 주문의 이벤트가 다시 오면 아무것도 하지 않는다")
    void isIdempotent() {
        Wallet holding = wallet(HOLDING_ID, HOLDING_WALLET_ID, "10000");
        stubHolding(holding);
        stubHoldingHas(RefType.SHIPPING_FEE, true);

        paymentService.collectShippingFee(SELLER_ORDER_ID, new BigDecimal("3000"));

        assertThat(holding.getBalance()).isEqualByComparingTo("10000");
        verify(walletTransactionRepository, never()).save(any());
        verify(memberQuery, never()).getRevenueMemberId(); // REVENUE 지갑은 잠그지 않는다
    }

    @Test
    @DisplayName("수거 전 취소로 이미 환불된 배송비면 옮기지 않는다")
    void skipsWhenAlreadyRefunded() {
        Wallet holding = wallet(HOLDING_ID, HOLDING_WALLET_ID, "10000");
        stubHolding(holding);
        stubHoldingHas(RefType.SHIPPING_FEE, false);
        stubHoldingHas(RefType.SHIPPING_FEE_REFUND, true);

        paymentService.collectShippingFee(SELLER_ORDER_ID, new BigDecimal("3000"));

        assertThat(holding.getBalance()).isEqualByComparingTo("10000");
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("HOLDING 잔액이 부족하면 INSUFFICIENT_BALANCE 예외로 Kafka 재시도를 유도한다")
    void throwsWhenHoldingInsufficient() {
        stubHolding(wallet(HOLDING_ID, HOLDING_WALLET_ID, "1000"));
        stubRevenue(wallet(REVENUE_ID, REVENUE_WALLET_ID, "0"));
        stubHoldingHas(RefType.SHIPPING_FEE, false);
        stubHoldingHas(RefType.SHIPPING_FEE_REFUND, false);

        assertThatThrownBy(() -> paymentService.collectShippingFee(SELLER_ORDER_ID, new BigDecimal("3000")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INSUFFICIENT_BALANCE);
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("배송비가 null이거나 0이면 지갑을 건드리지 않고 끝낸다")
    void skipsWhenNoShippingFee() {
        paymentService.collectShippingFee(SELLER_ORDER_ID, null);
        paymentService.collectShippingFee(SELLER_ORDER_ID, BigDecimal.ZERO);

        verifyNoInteractions(walletRepository, walletTransactionRepository, memberQuery);
    }

    @Test
    @DisplayName("판매자 주문 ID가 없거나 배송비가 음수면 INVALID_AMOUNT 예외")
    void rejectsInvalidRequest() {
        assertInvalid(null, new BigDecimal("3000"));
        assertInvalid(SELLER_ORDER_ID, new BigDecimal("-1"));

        verifyNoInteractions(walletRepository, walletTransactionRepository, memberQuery);
    }

    // ===== helpers =====

    private void assertInvalid(Long sellerOrderId, BigDecimal shippingFee) {
        assertThatThrownBy(() -> paymentService.collectShippingFee(sellerOrderId, shippingFee))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_AMOUNT);
    }

    private void stubHolding(Wallet holding) {
        when(memberQuery.getHoldingMemberId()).thenReturn(HOLDING_ID);
        when(walletRepository.findByMemberIdForUpdate(HOLDING_ID)).thenReturn(Optional.of(holding));
    }

    private void stubRevenue(Wallet revenue) {
        when(memberQuery.getRevenueMemberId()).thenReturn(REVENUE_ID);
        when(walletRepository.findByMemberIdForUpdate(REVENUE_ID)).thenReturn(Optional.of(revenue));
    }

    private void stubHoldingHas(RefType refType, boolean exists) {
        when(walletTransactionRepository.existsByWallet_IdAndRefTypeAndRefId(HOLDING_WALLET_ID, refType, SELLER_ORDER_ID))
                .thenReturn(exists);
    }

    private List<WalletTransaction> capturedTransactions(int expectedCount) {
        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(walletTransactionRepository, times(expectedCount)).save(captor.capture());
        return captor.getAllValues();
    }

    private Wallet wallet(Long memberId, Long walletId, String balance) {
        Wallet wallet = new Wallet(memberId);
        ReflectionTestUtils.setField(wallet, "id", walletId);
        if (new BigDecimal(balance).signum() > 0) {
            wallet.credit(new BigDecimal(balance));
        }
        return wallet;
    }
}