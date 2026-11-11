package com.justcommit.backend.settlement.domain;

import com.justcommit.backend.settlement.common.entity.BaseTimeEntity;
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

    // 배송비: 수수료 · 정산금 계산에 넣지 않음. 옛 토픽으로 받은 정산은 null
    @Column(name = "shipping_fee", precision = 19)
    private BigDecimal shippingFee;

    @Column(name = "payout_amount", nullable = false, precision = 19)
    private BigDecimal payoutAmount;

    @Column(name = "settled_at", nullable = false)
    private LocalDateTime settledAt;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "item_summary")
    private String itemSummary;


    private Settlement(Long sellerOrderId, Long sellerId,
                       BigDecimal saleAmount, BigDecimal fee, BigDecimal shippingFee,
                       LocalDateTime settledAt, String itemSummary) {
        this.sellerOrderId = sellerOrderId;
        this.sellerId = sellerId;
        this.saleAmount = saleAmount;
        this.fee = fee;
        this.shippingFee = shippingFee;
        this.payoutAmount = saleAmount.subtract(fee);
        this.settledAt = settledAt;
        this.itemSummary = itemSummary;
    }

    public static Settlement createSettlement(
            Long sellerOrderId,
            Long sellerId,
            BigDecimal saleAmount,
            BigDecimal shippingFee,
            LocalDateTime settledAt,
            String itemSummary
    ) {
        validateAmount(saleAmount);

        BigDecimal fee = SettlementPolicy.calculateFee(saleAmount);

        return new Settlement(
                sellerOrderId,
                sellerId,
                saleAmount,
                fee,
                shippingFee,
                settledAt,
                itemSummary
        );
    }

    // 옛 토픽(배송비 없음)으로 먼저 정산된 뒤 새 토픽이 오면 비어 있는 배송비만 채운다. 이미 값이 있으면 바꾸지 않음.
    // 지금은 기록만 한다. 정산 지급 때 배송비를 REVENUE로 옮기게 되면, 지급 뒤에 채워진 배송비는 옮겨지지 않으니 그때 다시 본다
    public void updateShippingFeeIfAbsent(BigDecimal shippingFee) {
        if (this.shippingFee == null && shippingFee != null) {
            this.shippingFee = shippingFee;
        }
    }

    private static void validateAmount(BigDecimal saleAmount) {
        if (saleAmount == null || saleAmount.signum() <= 0) {
            throw new BusinessException(SettlementErrorCode.INVALID_AMOUNT);
        }
    }
}