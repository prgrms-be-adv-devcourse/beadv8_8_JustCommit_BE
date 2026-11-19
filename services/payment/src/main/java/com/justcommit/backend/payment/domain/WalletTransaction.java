package com.justcommit.backend.payment.domain;

import com.justcommit.backend.payment.common.entity.BaseTimeEntity;
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

    @Column(name = "receivable_after", nullable = false, precision = 19)
    private BigDecimal receivableAfter;

    public static WalletTransaction chargeIn(Wallet wallet, Charge charge) {
        return new WalletTransaction(
                wallet,
                RefType.CHARGE,
                charge.getId(),
                charge.getApprovedAt(),
                TransactionStatus.IN,
                charge.getAmount(),
                wallet.getBalance(),
                wallet.getReceivable()
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
                wallet.getBalance(),
                wallet.getReceivable()
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
                wallet.getBalance(),
                wallet.getReceivable()
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
                wallet.getBalance(),
                wallet.getReceivable()
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
                wallet.getBalance(),
                wallet.getReceivable()
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
                wallet.getBalance(),
                wallet.getReceivable()
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
                wallet.getBalance(),
                wallet.getReceivable()
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
                TransactionStatus.IN, amount, wallet.getBalance(), wallet.getReceivable());
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
                TransactionStatus.OUT, amount, wallet.getBalance(), wallet.getReceivable());
    }

    // 판매자 정산 행. 정산마다 1건(중복 지급 판정 기준). 0원 · 마이너스 정산이면 금액 0으로 잔액 변경 없이 기록한다
    public static WalletTransaction sellerSettlement(
            Wallet wallet,
            Long settlementId,
            BigDecimal amount,
            LocalDateTime occurredAt
    ) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("판매자 정산 행 금액은 0 이상이어야 합니다: " + amount);
        }
        return new WalletTransaction(
                wallet,
                RefType.SETTLEMENT,
                settlementId,
                occurredAt,
                TransactionStatus.IN,
                amount,
                wallet.getBalance(),
                wallet.getReceivable()
        );
    }

    // 마이너스 정산을 판매자 잔액에서 회수한 출금
    public static WalletTransaction settlementCollectionOut(
            Wallet wallet,
            Long settlementId,
            BigDecimal amount,
            LocalDateTime occurredAt
    ) {
        return new WalletTransaction(
                wallet,
                RefType.SETTLEMENT_COLLECTION,
                settlementId,
                occurredAt,
                TransactionStatus.OUT,
                amount,
                wallet.getBalance(),
                wallet.getReceivable()
        );
    }

    // REVENUE 판매자 부담금 적립 (실제로 받은 금액만)
    public static WalletTransaction sellerBurdenIn(
            Wallet wallet,
            Long settlementId,
            BigDecimal amount,
            LocalDateTime occurredAt
    ) {
        return new WalletTransaction(
                wallet,
                RefType.SELLER_BURDEN,
                settlementId,
                occurredAt,
                TransactionStatus.IN,
                amount,
                wallet.getBalance(),
                wallet.getReceivable()
        );
    }

    // 미수금 차감: 회원 지갑에서 나간 금액 (ref_id = 원인이 된 입금 거래 행 ID)
    public static WalletTransaction receivableCollectionOut(
            Wallet memberWallet,
            Long depositTransactionId,
            BigDecimal amount,
            LocalDateTime occurredAt
    ) {
        return new WalletTransaction(
                memberWallet,
                RefType.RECEIVABLE_COLLECTION,
                depositTransactionId,
                occurredAt,
                TransactionStatus.OUT,
                amount,
                memberWallet.getBalance(),
                memberWallet.getReceivable()
        );
    }

    // 미수금 차감: REVENUE에 들어온 금액 (ref_id = 원인이 된 입금 거래 행 ID)
    public static WalletTransaction receivableCollectionIn(
            Wallet revenueWallet,
            Long depositTransactionId,
            BigDecimal amount,
            LocalDateTime occurredAt
    ) {
        return new WalletTransaction(
                revenueWallet,
                RefType.RECEIVABLE_COLLECTION,
                depositTransactionId,
                occurredAt,
                TransactionStatus.IN,
                amount,
                revenueWallet.getBalance(),
                revenueWallet.getReceivable()
        );
    }
}
