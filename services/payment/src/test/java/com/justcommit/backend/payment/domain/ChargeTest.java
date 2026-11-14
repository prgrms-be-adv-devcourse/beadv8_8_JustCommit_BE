package com.justcommit.backend.payment.domain;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargeTest {

    private final Wallet wallet = Wallet.forMember(1L);

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

    @Test
    void startConfirmChangesReadyToPendingAndStoresPaymentKey() {
        Charge charge = readyCharge();

        charge.startConfirm("encrypted-key");

        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.PENDING);
        assertThat(charge.getPgPaymentKeyEnc()).isEqualTo("encrypted-key");
    }

    @Test
    void startConfirmRejectsPendingAsProcessing() {
        Charge charge = pendingCharge();

        assertThatThrownBy(() -> charge.startConfirm("encrypted-key"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.CHARGE_PROCESSING);
    }

    @Test
    void startConfirmRejectsApprovedAsAlreadyProcessed() {
        Charge charge = pendingCharge();
        charge.completeConfirm(LocalDateTime.now());

        assertThatThrownBy(() -> charge.startConfirm("encrypted-key"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.CHARGE_ALREADY_PROCESSED);
    }

    @Test
    void completeConfirmChangesPendingToApproved() {
        Charge charge = pendingCharge();
        LocalDateTime approvedAt = LocalDateTime.now();

        charge.completeConfirm(approvedAt);

        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.APPROVED);
        assertThat(charge.getApprovedAt()).isEqualTo(approvedAt);
    }

    @Test
    void completeConfirmRejectsNotPending() {
        Charge charge = readyCharge();

        assertThatThrownBy(() -> charge.completeConfirm(LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.CHARGE_ALREADY_PROCESSED);
    }

    @Test
    void failChangesPendingToFailed() {
        Charge charge = pendingCharge();

        charge.fail();

        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.FAILED);
    }

    @Test
    void failRejectsNotPending() {
        Charge charge = readyCharge();

        assertThatThrownBy(charge::fail)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.CHARGE_ALREADY_PROCESSED);
    }

    private Charge readyCharge() {
        return Charge.ready(wallet, ChargeType.TOSS, new BigDecimal("10000"));
    }

    private Charge pendingCharge() {
        Charge charge = readyCharge();
        charge.startConfirm("encrypted-key");
        return charge;
    }
}