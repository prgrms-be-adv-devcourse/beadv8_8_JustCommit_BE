package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.MemberQuery;
import com.justcommit.backend.payment.PaymentResult;
import com.justcommit.backend.payment.PaymentStatus;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Long ORDER_ID = 100L;
    private static final Long BUYER_ID = 10L;
    private static final Long WALLET_ID = 1L;
    private static final Long HOLDING_ID = 999L;
    private static final Long HOLDING_WALLET_ID = 2L;

    @Mock WalletRepository walletRepository;
    @Mock WalletTransactionRepository walletTransactionRepository;
    @Mock MemberQuery memberQuery;
    @InjectMocks PaymentService paymentService;

    @Test
    @DisplayName("결제 성공 시 구매자 지갑에서 차감하고 같은 금액을 HOLDING 지갑에 적립한다")
    void debitsBuyerAndCreditsHolding() {
        Wallet buyerWallet = buyerWalletWithBalance("10000");
        Wallet holdingWallet = holdingWallet();
        stubBuyerWallet(buyerWallet);
        stubHoldingWallet(holdingWallet);

        PaymentResult result = paymentService.pay(ORDER_ID, BUYER_ID, new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(result.isPaid()).isTrue();
        assertThat(result.balanceAfter()).isEqualByComparingTo("7000");
        assertThat(buyerWallet.getBalance()).isEqualByComparingTo("7000");
        assertThat(holdingWallet.getBalance()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("결제 성공 시 구매자 출금 내역과 HOLDING 입금 내역이 각각 저장된다")
    void recordsBuyerOutAndHoldingInTransactions() {
        stubBuyerWallet(buyerWalletWithBalance("10000"));
        stubHoldingWallet(holdingWallet());

        paymentService.pay(ORDER_ID, BUYER_ID, new BigDecimal("3000"));

        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(walletTransactionRepository, times(2)).save(captor.capture());
        List<WalletTransaction> saved = captor.getAllValues();

        WalletTransaction buyerOut = saved.get(0);
        assertThat(buyerOut.getRefType()).isEqualTo(RefType.PAYMENT);
        assertThat(buyerOut.getRefId()).isEqualTo(ORDER_ID);
        assertThat(buyerOut.getStatus()).isEqualTo(TransactionStatus.OUT);
        assertThat(buyerOut.getAmount()).isEqualByComparingTo("3000");
        assertThat(buyerOut.getBalanceAfter()).isEqualByComparingTo("7000");

        WalletTransaction holdingIn = saved.get(1);
        assertThat(holdingIn.getRefType()).isEqualTo(RefType.PAYMENT);
        assertThat(holdingIn.getRefId()).isEqualTo(ORDER_ID);
        assertThat(holdingIn.getStatus()).isEqualTo(TransactionStatus.IN);
        assertThat(holdingIn.getAmount()).isEqualByComparingTo("3000");
        assertThat(holdingIn.getBalanceAfter()).isEqualByComparingTo("3000");

        // 같은 결제 사건이라 두 내역의 발생 시각이 같아야 한다
        assertThat(buyerOut.getRefOccurredTime()).isNotNull();
        assertThat(holdingIn.getRefOccurredTime()).isEqualTo(buyerOut.getRefOccurredTime());
    }

    @Test
    @DisplayName("잔액과 결제 금액이 같으면 잔액 전부를 HOLDING으로 옮긴다")
    void debitsWholeBalanceWhenAmountEqualsBalance() {
        Wallet holdingWallet = holdingWallet();
        stubBuyerWallet(buyerWalletWithBalance("3000"));
        stubHoldingWallet(holdingWallet);

        PaymentResult result = paymentService.pay(ORDER_ID, BUYER_ID, new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(result.balanceAfter()).isEqualByComparingTo("0");
        assertThat(holdingWallet.getBalance()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("HOLDING 지갑이 없으면 예외를 던진다")
    void throwsWhenHoldingWalletMissing() {
        stubBuyerWallet(buyerWalletWithBalance("10000"));
        when(memberQuery.getHoldingMemberId()).thenReturn(HOLDING_ID);
        when(walletRepository.findByMemberIdForUpdate(HOLDING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.pay(ORDER_ID, BUYER_ID, new BigDecimal("3000")))
                .isInstanceOf(BusinessException.class);
        // 실제 잔액·내역 롤백은 단위 테스트로 확인할 수 없어 통합 테스트에서 검증한다
    }

    @Test
    @DisplayName("잔액이 부족하면 차감하지 않고 HOLDING도 건드리지 않는다")
    void returnsInsufficientBalanceWithoutDebiting() {
        Wallet buyerWallet = buyerWalletWithBalance("2000");
        stubBuyerWallet(buyerWallet);

        PaymentResult result = paymentService.pay(ORDER_ID, BUYER_ID, new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(PaymentStatus.INSUFFICIENT_BALANCE);
        assertThat(result.isPaid()).isFalse();
        assertThat(result.balanceAfter()).isEqualByComparingTo("2000");
        assertThat(buyerWallet.getBalance()).isEqualByComparingTo("2000");
        verify(walletTransactionRepository, never()).save(any());
        verifyNoInteractions(memberQuery);
    }

    @Test
    @DisplayName("구매자 지갑이 없으면 WALLET_NOT_FOUND를 반환한다")
    void returnsWalletNotFoundWhenBuyerHasNoWallet() {
        when(walletRepository.findByMemberIdForUpdate(BUYER_ID)).thenReturn(Optional.empty());

        PaymentResult result = paymentService.pay(ORDER_ID, BUYER_ID, new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(PaymentStatus.WALLET_NOT_FOUND);
        assertThat(result.isPaid()).isFalse();
        assertThat(result.balanceAfter()).isNull();
        verifyNoInteractions(walletTransactionRepository, memberQuery);
    }

    @Test
    @DisplayName("이미 결제된 주문이면 다시 차감하지 않고 HOLDING도 건드리지 않는다")
    void returnsAlreadyPaidWithoutDebitingTwice() {
        Wallet buyerWallet = buyerWalletWithBalance("7000");
        when(walletRepository.findByMemberIdForUpdate(BUYER_ID)).thenReturn(Optional.of(buyerWallet));
        when(walletTransactionRepository.existsByWallet_IdAndRefTypeAndRefId(WALLET_ID, RefType.PAYMENT, ORDER_ID))
                .thenReturn(true);

        PaymentResult result = paymentService.pay(ORDER_ID, BUYER_ID, new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(PaymentStatus.ALREADY_PAID);
        assertThat(result.isPaid()).isTrue();
        assertThat(buyerWallet.getBalance()).isEqualByComparingTo("7000");
        verify(walletTransactionRepository, never()).save(any());
        verifyNoInteractions(memberQuery);
    }

    @Test
    void rejectsZeroOrNegativeAmount() {
        assertThatThrownBy(() -> paymentService.pay(ORDER_ID, BUYER_ID, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> paymentService.pay(ORDER_ID, BUYER_ID, new BigDecimal("-1")))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(walletRepository, walletTransactionRepository, memberQuery);
    }

    @Test
    void rejectsMissingArguments() {
        assertThatThrownBy(() -> paymentService.pay(null, BUYER_ID, new BigDecimal("1000")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> paymentService.pay(ORDER_ID, null, new BigDecimal("1000")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> paymentService.pay(ORDER_ID, BUYER_ID, null))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(walletRepository, walletTransactionRepository, memberQuery);
    }

    // 구매자 지갑 조회 + 중복 결제 아님
    private void stubBuyerWallet(Wallet buyerWallet) {
        when(walletRepository.findByMemberIdForUpdate(BUYER_ID)).thenReturn(Optional.of(buyerWallet));
        when(walletTransactionRepository.existsByWallet_IdAndRefTypeAndRefId(WALLET_ID, RefType.PAYMENT, ORDER_ID))
                .thenReturn(false);
    }

    // HOLDING 회원 ID 조회 + HOLDING 지갑 조회
    private void stubHoldingWallet(Wallet holdingWallet) {
        when(memberQuery.getHoldingMemberId()).thenReturn(HOLDING_ID);
        when(walletRepository.findByMemberIdForUpdate(HOLDING_ID)).thenReturn(Optional.of(holdingWallet));
    }

    private Wallet buyerWalletWithBalance(String balance) {
        Wallet wallet = new Wallet(BUYER_ID);
        wallet.credit(new BigDecimal(balance));
        ReflectionTestUtils.setField(wallet, "id", WALLET_ID);
        return wallet;
    }

    private Wallet holdingWallet() {
        Wallet wallet = new Wallet(HOLDING_ID);
        ReflectionTestUtils.setField(wallet, "id", HOLDING_WALLET_ID);
        return wallet;
    }
}