package com.justcommit.backend.payment.domain;

import com.justcommit.backend.payment.common.entity.BaseTimeEntity;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Table(
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_charge_type_pg_order_no",
                        columnNames = {"charge_type", "pg_order_no"}
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Charge extends BaseTimeEntity {

    private static final BigDecimal MIN_CHARGE_AMOUNT = new BigDecimal("10000");
    private static final BigDecimal CHARGE_UNIT = new BigDecimal("10000");

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", nullable = false)
    private ChargeType chargeType;

    @Column(name = "pg_order_no", length = 64)
    private String pgOrderNo;

    @Column(name = "amount", nullable = false, precision = 19)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ChargeStatus status;

    @Column(name = "pg_payment_key_enc", length = 1024)
    private String pgPaymentKeyEnc;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    private Charge(Wallet wallet, ChargeType chargeType, String pgOrderNo, BigDecimal amount) {
        this.wallet = wallet;
        this.chargeType = chargeType;
        this.pgOrderNo = pgOrderNo;
        this.amount = amount;
        this.status = ChargeStatus.READY;
    }

    public static Charge ready(Wallet wallet, ChargeType chargeType, BigDecimal amount) {
        validateAmount(amount);
        return new Charge(wallet, chargeType, generatePgOrderNo(), amount);
    }

    // TX1: 승인 요청이 들어오면 토스 호출 전에 PENDING으로 바꾸고 paymentKey를 저장한다
    public void startConfirm(String encryptedPaymentKey) {
        validateReady();
        this.status = ChargeStatus.PENDING;
        this.pgPaymentKeyEnc = encryptedPaymentKey;
    }

    // TX2: 토스 승인 성공
    public void completeConfirm(LocalDateTime approvedAt) {
        validatePending();
        this.status = ChargeStatus.APPROVED;
        this.approvedAt = approvedAt;
    }

    // TX2: 토스가 승인을 명확히 거절
    public void fail() {
        validatePending();
        this.status = ChargeStatus.FAILED;
    }

    private void validatePending() {
        if (this.status != ChargeStatus.PENDING) {
            throw new BusinessException(PaymentErrorCode.CHARGE_ALREADY_PROCESSED);
        }
    }

    public void validateConfirmInfo(String pgOrderNo, BigDecimal amount) {
        if (!this.pgOrderNo.equals(pgOrderNo) || this.amount.compareTo(amount) != 0) {
            throw new BusinessException(PaymentErrorCode.CHARGE_INFO_MISMATCH);
        }
    }

    private static void validateAmount(BigDecimal amount) {
        if (amount == null
                || amount.compareTo(MIN_CHARGE_AMOUNT) < 0
                || amount.remainder(CHARGE_UNIT).signum() != 0) {
            throw new BusinessException(PaymentErrorCode.INVALID_CHARGE_AMOUNT);
        }
    }

    private void validateReady() {
        if (this.status == ChargeStatus.PENDING) {
            throw new BusinessException(PaymentErrorCode.CHARGE_PROCESSING);
        }
        if (this.status != ChargeStatus.READY) {
            throw new BusinessException(PaymentErrorCode.CHARGE_ALREADY_PROCESSED);
        }
    }

    private static String generatePgOrderNo() {
        return "CHG-" + UUID.randomUUID();
    }
}
