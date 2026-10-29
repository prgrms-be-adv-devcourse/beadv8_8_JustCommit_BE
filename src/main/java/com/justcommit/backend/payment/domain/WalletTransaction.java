package com.justcommit.backend.payment.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@Table(
        name = "wallet_transaction",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_wallet_transaction_ref",
                        columnNames = {"wallet_id", "ref_type", "ref_id"}
                )
        }
)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletTransaction extends BaseTimeEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_type", nullable = false)
    private RefType refType;

    @Column(name = "ref_id", nullable = false)
    private Long refId;

    @Column(name = "ref_occurred_time", nullable = false)
    private LocalDateTime refOccurredTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(nullable = false, precision = 19)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19)
    private BigDecimal balanceAfter;

    public static WalletTransaction chargeIn(Wallet wallet, Charge charge) {
        return new WalletTransaction(
                wallet,
                RefType.CHARGE,
                charge.getId(),
                charge.getApprovedAt(),
                TransactionStatus.IN,
                charge.getAmount(),
                wallet.getBalance()
        );
    }

    public static WalletTransaction paymentOut(Wallet wallet, Long orderId, BigDecimal amount, LocalDateTime occurredAt) {
        return new WalletTransaction(
                wallet,
                RefType.PAYMENT,
                orderId,
                occurredAt,
                TransactionStatus.OUT,
                amount,
                wallet.getBalance()
        );
    }

    public static WalletTransaction settlementIn(
            Wallet wallet,
            Long settlementId,
            BigDecimal amount,
            LocalDateTime occurredAt
    ) {
        return new WalletTransaction(
                wallet,
                RefType.SETTLEMENT,
                settlementId,
                occurredAt,
                TransactionStatus.IN,
                amount,
                wallet.getBalance()
        );
    }

    // HOLDING 정산금 출금
    public static WalletTransaction settlementOut(
            Wallet wallet,
            Long settlementId,
            BigDecimal amount,
            LocalDateTime occurredAt
    ) {
        return new WalletTransaction(
                wallet,
                RefType.SETTLEMENT,
                settlementId,
                occurredAt,
                TransactionStatus.OUT,
                amount,
                wallet.getBalance()
        );
    }

    public static WalletTransaction holdingIn(
            Wallet wallet,
            Long orderId,
            BigDecimal amount,
            LocalDateTime occurredAt
    ) {
        return new WalletTransaction(
                wallet,
                RefType.PAYMENT,
                orderId,
                occurredAt,
                TransactionStatus.IN,
                amount,
                wallet.getBalance()
        );
    }

    // REVENUE 수수료 적립
    public static WalletTransaction settlementFeeIn(
            Wallet wallet,
            Long settlementId,
            BigDecimal amount,
            LocalDateTime occurredAt
    ) {
        return new WalletTransaction(
                wallet,
                RefType.SETTLEMENT_FEE,
                settlementId,
                occurredAt,
                TransactionStatus.IN,
                amount,
                wallet.getBalance()
        );
    }

    // HOLDING 배송비 출금 (택배 수거 시)
    public static WalletTransaction shippingFeeOut(
            Wallet wallet,
            Long sellerOrderId,
            BigDecimal amount,
            LocalDateTime occurredAt
    ) {
        return new WalletTransaction(
                wallet,
                RefType.SHIPPING_FEE,
                sellerOrderId,
                occurredAt,
                TransactionStatus.OUT,
                amount,
                wallet.getBalance()
        );
    }

    // REVENUE 배송비 적립 (택배 수거 시)
    public static WalletTransaction shippingFeeIn(
            Wallet wallet,
            Long sellerOrderId,
            BigDecimal amount,
            LocalDateTime occurredAt
    ) {
        return new WalletTransaction(
                wallet,
                RefType.SHIPPING_FEE,
                sellerOrderId,
                occurredAt,
                TransactionStatus.IN,
                amount,
                wallet.getBalance()
        );
    }

    public static WalletTransaction refundIn(
            Wallet wallet,
            RefType refType,
            Long refId,
            BigDecimal amount,
            LocalDateTime occurredAt
    ) {
        return new WalletTransaction(
                wallet, refType, refId, occurredAt,
                TransactionStatus.IN, amount, wallet.getBalance());
    }

    public static WalletTransaction refundOut(
            Wallet wallet,
            RefType refType,
            Long refId,
            BigDecimal amount,
            LocalDateTime occurredAt
    ) {
        return new WalletTransaction(
                wallet, refType, refId, occurredAt,
                TransactionStatus.OUT, amount, wallet.getBalance());
    }
}
