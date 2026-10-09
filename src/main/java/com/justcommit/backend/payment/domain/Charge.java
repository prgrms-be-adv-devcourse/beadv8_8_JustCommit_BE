package com.justcommit.backend.payment.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
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

    private static final BigDecimal MIN_CHARGE_AMOUNT = BigDecimal.ONE;

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

    @Column(name = "pg_payment_key_enc")
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

    public void approve(String pgPaymentKey, LocalDateTime approvedAt) {
        validateReady();
        this.status = ChargeStatus.APPROVED;
        this.pgPaymentKeyEnc = pgPaymentKey;   // TODO: 암호화
        this.approvedAt = approvedAt;
    }

    public void validateConfirmInfo(String pgOrderNo, BigDecimal amount) {
        if (!this.pgOrderNo.equals(pgOrderNo) || this.amount.compareTo(amount) != 0) {
            throw new BusinessException(PaymentErrorCode.CHARGE_INFO_MISMATCH);
        }
    }

    private static void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(MIN_CHARGE_AMOUNT) < 0) {
            throw new BusinessException(PaymentErrorCode.INVALID_CHARGE_AMOUNT);
        }
    }

    public void validateReady() {
        if (this.status != ChargeStatus.READY) {
            throw new BusinessException(PaymentErrorCode.CHARGE_ALREADY_PROCESSED);
        }
    }

    private static String generatePgOrderNo() {
        return "CHG-" + UUID.randomUUID();
    }
}
