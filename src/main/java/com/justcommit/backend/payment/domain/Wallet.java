package com.justcommit.backend.payment.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet extends BaseTimeEntity {

    @Column(name = "member_id", nullable = false, unique = true)
    private Long memberId;

    @Column(name = "balance", nullable = false)
    private Long balance = 0L;

    @Version
    @Column(name = "version")
    private Long version;

    public Wallet(Long memberId) {
        this.memberId = memberId;
    }

    public void credit(long amount) {
        if(amount <= 0) {
            throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT);
        }
        balance += amount;
    }

    public void debit(long amount) {
        if (amount <= 0) {
            throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT);
        }

        if (this.balance < amount) {
            throw new BusinessException(PaymentErrorCode.INSUFFICIENT_BALANCE);
        }

        this.balance -= amount;
    }
}
