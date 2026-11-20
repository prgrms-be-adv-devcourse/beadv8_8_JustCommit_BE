package com.justcommit.backend.payment.domain;

import com.justcommit.backend.payment.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 인출 건. 신청 시점 계좌를 스냅샷으로 남긴다 (계좌번호는 암호문과 뒤 4자리만)
// 상태 변경(PENDING → COMPLETED)은 저장소의 조건부 update로만 한다
@Entity
@Getter
@Table(
        name = "cash_out",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_cash_out_wallet_idempotency_key",
                        columnNames = {"wallet_id", "idempotency_key"}
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CashOut extends BaseTimeEntity {

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(nullable = false, precision = 19)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CashOutStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "bank_code", nullable = false, length = 10)
    private String bankCode;

    @Column(name = "account_no_enc", nullable = false, length = 512)
    private String accountNoEnc;

    @Column(name = "account_last4", nullable = false, length = 4)
    private String accountLast4;

    @Column(name = "account_holder", nullable = false, length = 50)
    private String accountHolder;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // 지급 재시도용 칸. 이번 버전에서는 쓰지 않는다
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    private CashOut(Long walletId, BigDecimal amount, String idempotencyKey, String bankCode,
                    String accountNoEnc, String accountLast4, String accountHolder, LocalDateTime requestedAt) {
        this.walletId = walletId;
        this.amount = amount;
        this.status = CashOutStatus.PENDING;
        this.idempotencyKey = idempotencyKey;
        this.bankCode = bankCode;
        this.accountNoEnc = accountNoEnc;
        this.accountLast4 = accountLast4;
        this.accountHolder = accountHolder;
        this.requestedAt = requestedAt;
    }

    // 잔액을 차감한 같은 트랜잭션에서 PENDING으로 만든다
    public static CashOut pending(Long walletId, BigDecimal amount, String idempotencyKey, String bankCode,
                                  String accountNoEnc, String accountLast4, String accountHolder,
                                  LocalDateTime requestedAt) {
        return new CashOut(walletId, amount, idempotencyKey, bankCode, accountNoEnc, accountLast4, accountHolder,
                requestedAt);
    }

    // 같은 키 재요청이 처음 요청과 같은지 (금액만 비교)
    public boolean isSameRequest(BigDecimal requestedAmount) {
        return requestedAmount != null && amount.compareTo(requestedAmount) == 0;
    }
}
