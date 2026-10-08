package com.justcommit.backend.payment.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    private static final long MIN_CHARGE_AMOUNT = 1L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", nullable = false)
    private ChargeType chargeType;

    @Column(name = "pg_order_no", length = 64)
    private String pgOrderNo;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ChargeStatus status;

    @Column(name = "pg_payment_key_enc")
    private String pgPaymentKeyEnc;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    private Charge(Wallet wallet, ChargeType chargeType, String pgOrderNo, Long amount) {
        this.wallet = wallet;
        this.chargeType = chargeType;
        this.pgOrderNo = pgOrderNo;
        this.amount = amount;
        this.status = ChargeStatus.READY;
    }

    public static Charge ready(Wallet wallet, ChargeType chargeType, Long amount) {
        validateAmount(amount);
        return new Charge(wallet, chargeType, generatePgOrderNo(), amount);
    }

    public void approve(String pgPaymentKey, LocalDateTime approvedAt) {
        validateReady();
        this.status = ChargeStatus.APPROVED;
        this.pgPaymentKeyEnc = pgPaymentKey;   // TODO: 암호화
        this.approvedAt = approvedAt;
    }

    public void validateConfirmInfo(String pgOrderNo, Long amount) {
        if (!this.pgOrderNo.equals(pgOrderNo) || !this.amount.equals(amount)) {
            throw new BusinessException(PaymentErrorCode.CHARGE_INFO_MISMATCH);
        }
    }

    private static void validateAmount(Long amount) {
        if (amount == null || amount < MIN_CHARGE_AMOUNT) {
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
