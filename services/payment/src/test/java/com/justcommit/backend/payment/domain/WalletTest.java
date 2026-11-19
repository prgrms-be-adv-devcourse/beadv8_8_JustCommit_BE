package com.justcommit.backend.payment.domain;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletTest {

    @Test
    @DisplayName("미수금은 0에서 시작하고 늘리고 줄일 수 있다")
    void increasesAndDecreasesReceivable() {
        Wallet wallet = Wallet.forMember(1L);
        assertThat(wallet.getReceivable()).isEqualByComparingTo("0");

        wallet.increaseReceivable(new BigDecimal("3000"));
        wallet.decreaseReceivable(new BigDecimal("1000"));

        assertThat(wallet.getReceivable()).isEqualByComparingTo("2000");
        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("미수금 증감 금액이 null이거나 0 이하면 INVALID_AMOUNT")
    void rejectsNonPositiveAmount() {
        Wallet wallet = Wallet.forMember(1L);
        wallet.increaseReceivable(new BigDecimal("1000"));

        for (BigDecimal amount : new BigDecimal[]{null, BigDecimal.ZERO, new BigDecimal("-1")}) {
            assertInvalid(() -> wallet.increaseReceivable(amount));
            assertInvalid(() -> wallet.decreaseReceivable(amount));
        }
        assertThat(wallet.getReceivable()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("미수금보다 많이 차감하면 INVALID_AMOUNT이고 미수금은 그대로다")
    void rejectsDecreaseBelowZero() {
        Wallet wallet = Wallet.forMember(1L);
        wallet.increaseReceivable(new BigDecimal("1000"));

        assertInvalid(() -> wallet.decreaseReceivable(new BigDecimal("1001")));
        assertThat(wallet.getReceivable()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("시스템 지갑에는 미수금을 둘 수 없다")
    void rejectsReceivableOnSystemWallet() {
        for (WalletType type : new WalletType[]{WalletType.HOLDING, WalletType.REVENUE}) {
            Wallet wallet = Wallet.system(type);
            assertThatThrownBy(() -> wallet.increaseReceivable(new BigDecimal("1000")))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> wallet.decreaseReceivable(new BigDecimal("1000")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_AMOUNT);
    }
}
