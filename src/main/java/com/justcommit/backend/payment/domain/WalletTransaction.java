package com.justcommit.backend.payment.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(
        name = "wallet_transaction",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_wallet_transaction_ref",
                        columnNames = {"ref_type", "ref_id"}
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

    @Column(nullable = false)
    private Long amount;

    @Column(name = "balance_after", nullable = false)
    private Long balanceAfter;

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
}
