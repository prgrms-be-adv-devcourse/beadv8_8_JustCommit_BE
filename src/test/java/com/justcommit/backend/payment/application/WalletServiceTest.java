package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import com.justcommit.backend.payment.presentation.dto.WalletTransactionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    private static final Long MEMBER_ID = 10L;
    private static final Long WALLET_ID = 1L;

    @Mock WalletRepository walletRepository;
    @Mock WalletTransactionRepository walletTransactionRepository;
    @InjectMocks WalletService walletService;

    private final Pageable pageable = PageRequest.of(0, 20);

    @Test
    void returnsAllTransactionsWhenRefTypeIsNull() {
        Wallet wallet = walletWithId();
        when(walletRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(wallet));
        List<WalletTransaction> transactions = List.of(
                WalletTransaction.paymentOut(wallet, 100L, new BigDecimal("3000"), LocalDateTime.now()),
                WalletTransaction.settlementIn(wallet, 200L, new BigDecimal("9000"), LocalDateTime.now()));
        when(walletTransactionRepository.findByWallet_Id(WALLET_ID, pageable))
                .thenReturn(new PageImpl<>(transactions, pageable, 2));

        Page<WalletTransactionResponse> result = walletService.getTransactions(MEMBER_ID, null, pageable);

        assertThat(result.getContent())
                .extracting(WalletTransactionResponse::getRefType)
                .containsExactly(RefType.PAYMENT, RefType.SETTLEMENT);
        verify(walletTransactionRepository, never()).findByWallet_IdAndRefType(any(), any(), any());
    }

    @Test
    void returnsOnlyTransactionsOfRequestedRefType() {
        Wallet wallet = walletWithId();
        when(walletRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(wallet));
        List<WalletTransaction> payments = List.of(
                WalletTransaction.paymentOut(wallet, 100L, new BigDecimal("3000"), LocalDateTime.now()));
        when(walletTransactionRepository.findByWallet_IdAndRefType(WALLET_ID, RefType.PAYMENT, pageable))
                .thenReturn(new PageImpl<>(payments, pageable, 1));

        Page<WalletTransactionResponse> result = walletService.getTransactions(MEMBER_ID, RefType.PAYMENT, pageable);

        assertThat(result.getContent()).hasSize(1);
        WalletTransactionResponse response = result.getContent().get(0);
        assertThat(response.getRefType()).isEqualTo(RefType.PAYMENT);
        assertThat(response.getRefId()).isEqualTo(100L);
        assertThat(response.getStatus()).isEqualTo(TransactionStatus.OUT);
        assertThat(response.getAmount()).isEqualByComparingTo("3000");
        verify(walletTransactionRepository, never()).findByWallet_Id(any(), any());
    }

    @Test
    void keepsPageInformationAfterMappingToResponse() {
        Wallet wallet = walletWithId();
        when(walletRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.of(wallet));
        List<WalletTransaction> payments = List.of(
                WalletTransaction.paymentOut(wallet, 100L, new BigDecimal("3000"), LocalDateTime.now()));

        when(walletTransactionRepository.findByWallet_IdAndRefType(WALLET_ID, RefType.PAYMENT, pageable))
                .thenReturn(new PageImpl<>(payments, pageable, 45));

        Page<WalletTransactionResponse> result = walletService.getTransactions(MEMBER_ID, RefType.PAYMENT, pageable);

        assertThat(result.getTotalElements()).isEqualTo(45);
        assertThat(result.getTotalPages()).isEqualTo(3);
        assertThat(result.getNumber()).isZero();
        assertThat(result.getSize()).isEqualTo(20);
    }

    @Test
    void throwsWalletNotFoundWhenMemberHasNoWallet() {
        when(walletRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.getTransactions(MEMBER_ID, RefType.CHARGE, pageable))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.WALLET_NOT_FOUND);
        verifyNoInteractions(walletTransactionRepository);
    }

    private Wallet walletWithId() {
        Wallet wallet = new Wallet(MEMBER_ID);
        ReflectionTestUtils.setField(wallet, "id", WALLET_ID);
        return wallet;
    }
}