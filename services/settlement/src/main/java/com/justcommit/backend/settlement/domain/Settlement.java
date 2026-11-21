package com.justcommit.backend.settlement.domain;

import com.justcommit.backend.settlement.common.entity.BaseTimeEntity;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.settlement.domain.exception.SettlementErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    private static final int LAST_ERROR_MAX_LENGTH = 500;

    @Column(name = "seller_order_id", nullable = false)
    private Long sellerOrderId;

    @Column(name = "sale_amount", nullable = false, precision = 19)
    private BigDecimal saleAmount;

    @Column(name = "fee", nullable = false, precision = 19)
    private BigDecimal fee;

    // 배송비: 수수료 · 정산금 계산에 넣지 않음. 옛 토픽으로 받은 정산은 null
    @Column(name = "shipping_fee", precision = 19)
    private BigDecimal shippingFee;

    // 판매자 부담금 (반품 배송비 등): 수수료 계산에 넣지 않고 정산금에서 뺀다. 부담금이 없으면 0
    @Column(name = "seller_burden_amount", nullable = false, precision = 19)
    private BigDecimal sellerBurdenAmount;

    // 정산금 = 판매액 - 수수료 - 판매자 부담금. 반품이 있으면 0원 · 음수일 수 있다
    @Column(name = "payout_amount", nullable = false, precision = 19)
    private BigDecimal payoutAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SettlementStatus status;

    // 정산 지급 시도 횟수 (실패할 때마다 1 증가)
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    // 다음 지급 시도 시각. 완료되면 null
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    // 마지막 지급 실패 사유. 성공하면 null
    @Column(name = "last_error", length = LAST_ERROR_MAX_LENGTH)
    private String lastError;

    // 지급 완료 또는 회수 요청 성공 시각. 반품으로 정산금이 음수면 회수로 끝나므로 settledAt이 아닌 completedAt. 완료 전 null
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "item_summary")
    private String itemSummary;


    private Settlement(Long sellerOrderId, Long sellerId,
                       BigDecimal saleAmount, BigDecimal fee, BigDecimal shippingFee,
                       BigDecimal sellerBurdenAmount, LocalDateTime requestedAt, String itemSummary) {
        this.sellerOrderId = sellerOrderId;
        this.sellerId = sellerId;
        this.saleAmount = saleAmount;
        this.fee = fee;
        this.shippingFee = shippingFee;
        this.sellerBurdenAmount = sellerBurdenAmount;
        this.payoutAmount = saleAmount.subtract(fee).subtract(sellerBurdenAmount);
        this.status = SettlementStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = requestedAt;
        this.itemSummary = itemSummary;
    }

    // 정산은 PENDING으로 만들고 requestedAt부터 지급 대상이 된다
    public static Settlement createSettlement(
            Long sellerOrderId,
            Long sellerId,
            BigDecimal saleAmount,
            BigDecimal shippingFee,
            BigDecimal sellerBurdenAmount,
            LocalDateTime requestedAt,
            String itemSummary
    ) {
        validateAmount(saleAmount);
        validateSellerBurdenAmount(sellerBurdenAmount);

        BigDecimal fee = SettlementPolicy.calculateFee(saleAmount);

        return new Settlement(
                sellerOrderId,
                sellerId,
                saleAmount,
                fee,
                shippingFee,
                sellerBurdenAmount,
                requestedAt,
                itemSummary
        );
    }

    // 전부 반품 정산 (#432): 구매 확정 상품이 없어 판매액, 수수료, 배송비가 0이고 정산금 = -판매자 부담금.
    // 원래 배송비는 market이 구매자에게 이미 환불해 정산 지급 때 REVENUE로 옮길 배송비가 없다.
    // 배송비가 0원인 판매자 주문이면 부담금도 0이라 정산금 0인 정산이 된다
    public static Settlement createReturnedSettlement(
            Long sellerOrderId,
            Long sellerId,
            BigDecimal sellerBurdenAmount,
            LocalDateTime requestedAt,
            String itemSummary
    ) {
        validateSellerBurdenAmount(sellerBurdenAmount);

        return new Settlement(
                sellerOrderId,
                sellerId,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                sellerBurdenAmount,
                requestedAt,
                itemSummary
        );
    }

    // 정산 지급 스케줄러가 이 건을 확보: leasedUntil까지 다른 실행이 다시 잡지 않게 다음 시도 시각을 미룬다 (#429)
    public void lease(LocalDateTime leasedUntil) {
        if (isCompleted()) {
            return;
        }
        this.nextAttemptAt = leasedUntil;
    }

    // 확보한 실행의 임대가 그대로인지 (임대가 끝나 다른 실행이 다시 잡았으면 false)
    public boolean isLeasedUntil(LocalDateTime leasedUntil) {
        return !isCompleted() && leasedUntil.equals(this.nextAttemptAt);
    }

    // 지급 성공. 이미 COMPLETED면 처음 완료 시각을 유지한다
    public void complete(LocalDateTime completedAt) {
        if (isCompleted()) {
            return;
        }
        this.status = SettlementStatus.COMPLETED;
        this.completedAt = completedAt;
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    // 지급 실패. COMPLETED 정산에는 반영하지 않는다 (늦게 온 실패 결과가 완료를 되돌리지 않게)
    public void fail(String reason, LocalDateTime nextAttemptAt) {
        if (isCompleted()) {
            return;
        }
        this.status = SettlementStatus.FAILED;
        this.attemptCount++;
        this.lastError = truncate(reason);
        this.nextAttemptAt = nextAttemptAt;
    }

    public boolean isCompleted() {
        return status == SettlementStatus.COMPLETED;
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= LAST_ERROR_MAX_LENGTH) {
            return reason;
        }
        return reason.substring(0, LAST_ERROR_MAX_LENGTH);
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

    // 부담금이 없으면 호출하는 쪽이 0으로 넘긴다 (null은 받지 않음)
    private static void validateSellerBurdenAmount(BigDecimal sellerBurdenAmount) {
        if (sellerBurdenAmount == null || sellerBurdenAmount.signum() < 0) {
            throw new BusinessException(SettlementErrorCode.INVALID_SELLER_BURDEN_AMOUNT);
        }
    }
}