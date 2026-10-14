package com.justcommit.backend.payment.domain;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargeTest {

    private final Wallet wallet = new Wallet(1L);

    @ParameterizedTest
    @ValueSource(strings = {"10000", "30000", "100000"})
    void createsReadyChargeWithValidAmount(String amount) {
        Charge charge = Charge.ready(wallet, ChargeType.TOSS, new BigDecimal(amount));

        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.READY);
        assertThat(charge.getAmount()).isEqualByComparingTo(amount);
        assertThat(charge.getPgOrderNo()).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {"9999", "5000", "15000", "10000.5", "0", "-10000"})
    void rejectsInvalidAmount(String amount) {
        assertThatThrownBy(() -> Charge.ready(wallet, ChargeType.TOSS, new BigDecimal(amount)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_CHARGE_AMOUNT);
    }

    @Test
    void rejectsNullAmount() {
        assertThatThrownBy(() -> Charge.ready(wallet, ChargeType.TOSS, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_CHARGE_AMOUNT);
    }
}