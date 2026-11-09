package com.justcommit.backend.payment.domain;

import com.justcommit.backend.payment.common.entity.BaseTimeEntity;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet extends BaseTimeEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "wallet_type", nullable = false, length = 20)
    private WalletType walletType;

    @Column(name = "member_id", unique = true)
    private Long memberId;

    @Column(name = "balance", nullable = false, precision = 19)
    private BigDecimal balance = BigDecimal.ZERO;

    @Version
    @Column(name = "version")
    private Long version;

    private Wallet(WalletType walletType, Long memberId) {
        this.walletType = walletType;
        this.memberId = memberId;
    }

    // 회원 지갑: memberId 필수
    public static Wallet forMember(Long memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("회원 지갑에는 memberId가 필요합니다.");
        }
        return new Wallet(WalletType.MEMBER, memberId);
    }

    // 시스템 지갑(HOLDING · REVENUE): memberId 없이 생성
    public static Wallet system(WalletType walletType) {
        if (walletType == null || walletType == WalletType.MEMBER) {
            throw new IllegalArgumentException("시스템 지갑 종류가 아닙니다: " + walletType);
        }
        return new Wallet(walletType, null);
    }

    public void credit(BigDecimal amount) {
        validateAmount(amount);
        balance = balance.add(amount);
    }

    public void debit(BigDecimal amount) {
        validateAmount(amount);

        if (!hasEnoughBalance(amount)) {
            throw new BusinessException(PaymentErrorCode.INSUFFICIENT_BALANCE);
        }

        this.balance = this.balance.subtract(amount);
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT);
        }
    }

    public boolean hasEnoughBalance(BigDecimal amount) {
        return amount != null && this.balance.compareTo(amount) >= 0;
    }
}
