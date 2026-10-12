package com.justcommit.backend.settlement.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.settlement.domain.exception.SettlementErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@Table(
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_settlement_seller_order_id",
                        columnNames = {"seller_order_id"}
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement extends BaseTimeEntity {

    @Column(name = "seller_order_id", nullable = false)
    private Long sellerOrderId;

    @Column(name = "sale_amount", nullable = false, precision = 19)
    private BigDecimal saleAmount;

    @Column(name = "fee", nullable = false, precision = 19)
    private BigDecimal fee;

    @Column(name = "payout_amount", nullable = false, precision = 19)
    private BigDecimal payoutAmount;

    @Column(name = "settled_at", nullable = false)
    private LocalDateTime settledAt;

    private Settlement(Long sellerOrderId, BigDecimal saleAmount, BigDecimal fee,
                       LocalDateTime settledAt) {
        this.sellerOrderId = sellerOrderId;
        this.saleAmount = saleAmount;
        this.fee = fee;
        this.payoutAmount = saleAmount.subtract(fee);
        this.settledAt = settledAt;
    }

    public static Settlement createSettlement(Long sellerOrderId, BigDecimal saleAmount, LocalDateTime settledAt) {
        validateAmount(saleAmount);
        BigDecimal fee = SettlementPolicy.calculateFee(saleAmount);
        return new Settlement(sellerOrderId, saleAmount, fee, settledAt);
    }

    private static void validateAmount(BigDecimal saleAmount) {
        if (saleAmount == null || saleAmount.signum() <= 0) {
            throw new BusinessException(SettlementErrorCode.INVALID_AMOUNT);
        }
    }
}