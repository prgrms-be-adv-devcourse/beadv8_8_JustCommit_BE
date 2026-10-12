package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.PaymentResult;
import com.justcommit.backend.payment.PaymentStatus;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Long ORDER_ID = 100L;
    private static final Long BUYER_ID = 10L;

    @Mock WalletRepository walletRepository;
    @Mock WalletTransactionRepository walletTransactionRepository;
    @InjectMocks PaymentService paymentService;

    @Test
    void debitsBalanceAndRecordsPaymentTransaction() {
        Wallet wallet = walletWithBalance("10000");
        when(walletRepository.findByMemberIdForUpdate(BUYER_ID)).thenReturn(Optional.of(wallet));
        when(walletTransactionRepository.existsByRefTypeAndRefId(RefType.PAYMENT, ORDER_ID)).thenReturn(false);

        PaymentResult result = paymentService.pay(ORDER_ID, BUYER_ID, new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(result.isPaid()).isTrue();
        assertThat(result.balanceAfter()).isEqualByComparingTo("7000");
        assertThat(wallet.getBalance()).isEqualByComparingTo("7000");

        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(walletTransactionRepository).save(captor.capture());
        WalletTransaction saved = captor.getValue();
        assertThat(saved.getRefType()).isEqualTo(RefType.PAYMENT);
        assertThat(saved.getRefId()).isEqualTo(ORDER_ID);
        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.OUT);
        assertThat(saved.getAmount()).isEqualByComparingTo("3000");
        assertThat(saved.getBalanceAfter()).isEqualByComparingTo("7000");
        assertThat(saved.getRefOccurredTime()).isNotNull();
    }

    @Test
    void debitsWholeBalanceWhenAmountEqualsBalance() {
        Wallet wallet = walletWithBalance("3000");
        when(walletRepository.findByMemberIdForUpdate(BUYER_ID)).thenReturn(Optional.of(wallet));
        when(walletTransactionRepository.existsByRefTypeAndRefId(RefType.PAYMENT, ORDER_ID)).thenReturn(false);

        PaymentResult result = paymentService.pay(ORDER_ID, BUYER_ID, new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(result.balanceAfter()).isEqualByComparingTo("0");
    }

    @Test
    void returnsInsufficientBalanceWithoutDebiting() {
        Wallet wallet = walletWithBalance("2000");
        when(walletRepository.findByMemberIdForUpdate(BUYER_ID)).thenReturn(Optional.of(wallet));
        when(walletTransactionRepository.existsByRefTypeAndRefId(RefType.PAYMENT, ORDER_ID)).thenReturn(false);

        PaymentResult result = paymentService.pay(ORDER_ID, BUYER_ID, new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(PaymentStatus.INSUFFICIENT_BALANCE);
        assertThat(result.isPaid()).isFalse();
        assertThat(result.balanceAfter()).isEqualByComparingTo("2000");
        assertThat(wallet.getBalance()).isEqualByComparingTo("2000");
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    void returnsWalletNotFoundWhenBuyerHasNoWallet() {
        when(walletRepository.findByMemberIdForUpdate(BUYER_ID)).thenReturn(Optional.empty());

        PaymentResult result = paymentService.pay(ORDER_ID, BUYER_ID, new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(PaymentStatus.WALLET_NOT_FOUND);
        assertThat(result.isPaid()).isFalse();
        assertThat(result.balanceAfter()).isNull();
        verifyNoInteractions(walletTransactionRepository);
    }

    @Test
    void returnsAlreadyPaidWithoutDebitingTwice() {
        Wallet wallet = walletWithBalance("7000");
        when(walletRepository.findByMemberIdForUpdate(BUYER_ID)).thenReturn(Optional.of(wallet));
        when(walletTransactionRepository.existsByRefTypeAndRefId(RefType.PAYMENT, ORDER_ID)).thenReturn(true);

        PaymentResult result = paymentService.pay(ORDER_ID, BUYER_ID, new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(PaymentStatus.ALREADY_PAID);
        assertThat(result.isPaid()).isTrue();
        assertThat(wallet.getBalance()).isEqualByComparingTo("7000");
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    void rejectsZeroOrNegativeAmount() {
        assertThatThrownBy(() -> paymentService.pay(ORDER_ID, BUYER_ID, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> paymentService.pay(ORDER_ID, BUYER_ID, new BigDecimal("-1")))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(walletRepository, walletTransactionRepository);
    }

    @Test
    void rejectsMissingArguments() {
        assertThatThrownBy(() -> paymentService.pay(null, BUYER_ID, new BigDecimal("1000")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> paymentService.pay(ORDER_ID, null, new BigDecimal("1000")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> paymentService.pay(ORDER_ID, BUYER_ID, null))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(walletRepository, walletTransactionRepository);
    }

    private Wallet walletWithBalance(String balance) {
        Wallet wallet = new Wallet(BUYER_ID);
        wallet.credit(new BigDecimal(balance));
        return wallet;
    }
}
