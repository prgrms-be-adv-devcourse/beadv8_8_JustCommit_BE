package com.justcommit.backend.payment.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
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

    @Column(name = "member_id", nullable = false, unique = true)
    private Long memberId;

    @Column(name = "balance", nullable = false, precision = 19)
    private BigDecimal balance = BigDecimal.ZERO;

    @Version
    @Column(name = "version")
    private Long version;

    public Wallet(Long memberId) {
        this.memberId = memberId;
    }

    public void credit(BigDecimal amount) {
        validateAmount(amount);
        balance = balance.add(amount);
    }

    public void debit(BigDecimal amount) {
        validateAmount(amount);

        if (this.balance.compareTo(amount) < 0) {
            throw new BusinessException(PaymentErrorCode.INSUFFICIENT_BALANCE);
        }

        this.balance = this.balance.subtract(amount);
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT);
        }
    }
}
