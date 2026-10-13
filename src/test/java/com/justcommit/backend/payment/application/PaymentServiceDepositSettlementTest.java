package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
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
class PaymentServiceDepositSettlementTest {

    private static final Long SETTLEMENT_ID = 200L;
    private static final Long SELLER_ID = 20L;

    @Mock WalletRepository walletRepository;
    @Mock WalletTransactionRepository walletTransactionRepository;
    @InjectMocks PaymentService paymentService;

    @Test
    void depositsPayoutAndRecordsSettlementTransaction() {
        Wallet wallet = walletWithBalance("5000");
        when(walletRepository.findByMemberIdForUpdate(SELLER_ID)).thenReturn(Optional.of(wallet));
        when(walletTransactionRepository.existsByRefTypeAndRefId(RefType.SETTLEMENT, SETTLEMENT_ID)).thenReturn(false);

        paymentService.depositSettlement(SETTLEMENT_ID, SELLER_ID, new BigDecimal("28500"));

        assertThat(wallet.getBalance()).isEqualByComparingTo("33500");

        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(walletTransactionRepository).save(captor.capture());
        WalletTransaction saved = captor.getValue();
        assertThat(saved.getRefType()).isEqualTo(RefType.SETTLEMENT);
        assertThat(saved.getRefId()).isEqualTo(SETTLEMENT_ID);
        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.IN);
        assertThat(saved.getAmount()).isEqualByComparingTo("28500");
        assertThat(saved.getBalanceAfter()).isEqualByComparingTo("33500");
        assertThat(saved.getRefOccurredTime()).isNotNull();
    }

    @Test
    void skipsDepositWhenSettlementAlreadyDeposited() {
        Wallet wallet = walletWithBalance("33500");
        when(walletRepository.findByMemberIdForUpdate(SELLER_ID)).thenReturn(Optional.of(wallet));
        when(walletTransactionRepository.existsByRefTypeAndRefId(RefType.SETTLEMENT, SETTLEMENT_ID)).thenReturn(true);

        paymentService.depositSettlement(SETTLEMENT_ID, SELLER_ID, new BigDecimal("28500"));

        assertThat(wallet.getBalance()).isEqualByComparingTo("33500");
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    void throwsWalletNotFoundWhenSellerHasNoWallet() {
        when(walletRepository.findByMemberIdForUpdate(SELLER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.depositSettlement(SETTLEMENT_ID, SELLER_ID, new BigDecimal("28500")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.WALLET_NOT_FOUND);

        verifyNoInteractions(walletTransactionRepository);
    }

    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> paymentService.depositSettlement(null, SELLER_ID, new BigDecimal("1000")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> paymentService.depositSettlement(SETTLEMENT_ID, null, new BigDecimal("1000")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> paymentService.depositSettlement(SETTLEMENT_ID, SELLER_ID, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> paymentService.depositSettlement(SETTLEMENT_ID, SELLER_ID, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(walletRepository, walletTransactionRepository);
    }

    private Wallet walletWithBalance(String balance) {
        Wallet wallet = new Wallet(SELLER_ID);
        wallet.credit(new BigDecimal(balance));
        return wallet;
    }
}