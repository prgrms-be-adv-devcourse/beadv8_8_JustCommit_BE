package com.justcommit.backend.payment.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;

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
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;




}
