package com.justcommit.backend.market.order.domain;

import com.justcommit.backend.market.common.entity.BaseTimeEntity;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.order.event.OrderItemCancelled;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders_item")
@NoArgsConstructor
@Getter
public class OrdersItem extends BaseTimeEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_order_id",  nullable = false)
    private SellerOrder sellerOrder;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private String productName;

    @Column(precision = 19, nullable = false)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderItemStatus status;

    // 환불 복구 선점 횟수 (첫 환불 호출은 세지 않음)
    @Column(nullable = false)
    private int attemptCount = 0;

    // 마지막 환불 시도 시각. 비어 있으면 택배 취소 결과를 기다리는 중
    @Column
    private LocalDateTime attemptedAt;

    // 환불 결과 불명, 확정 실패 원인 코드
    @Column(length = 100)
    private String lastError;

    // 취소 사유 (구매자 취소, 판매자 거절, 자동 거절). 복구가 판매 재개 이벤트의 reason으로 쓴다 (#284)
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private OrderItemCancelled.CancelReason cancelReason;

    public OrdersItem(SellerOrder sellerOrder, Long productId, String productName, BigDecimal price) {
        this.sellerOrder = sellerOrder;
        this.productId = productId;
        this.productName = productName;
        this.price = price;
        this.status = OrderItemStatus.NORMAL;
    }

    public void confirm() {
        if (this.status != OrderItemStatus.NORMAL) {
            throw new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_CONFIRMABLE);
        }
        this.status = OrderItemStatus.CONFIRMED;
    }

    // 판매자 수락: 수락 대기 → 일반 (#284)
    public void accept() {
        if (this.status != OrderItemStatus.ACCEPT_PENDING) {
            throw new IllegalStateException("수락 대기 주문 상품만 수락할 수 있습니다.");
        }
        this.status = OrderItemStatus.NORMAL;
    }

    // 구매자 취소, 판매자 거절을 받는다. 환불이 확정되기 전까지 취소 중 (#282, #284).
    // refundStartedAt: 바로 환불하면(배송 유지, 배송 없음) 그 시각, 택배 취소 결과를 기다리면 null
    public void requestCancel(LocalDateTime refundStartedAt, OrderItemCancelled.CancelReason reason) {
        if (this.status != OrderItemStatus.NORMAL && this.status != OrderItemStatus.ACCEPT_PENDING) {
            throw new IllegalStateException("일반, 수락 대기 상태의 주문 상품만 취소할수 있습니다.");
        }
        this.status = OrderItemStatus.CANCEL_PROCESSING;
        this.cancelReason = reason;
        this.attemptCount = 0;
        this.attemptedAt = refundStartedAt;
        this.lastError = null;
    }

    // 취소 사유. 비어 있으면 구매자 취소로 본다 (V9가 기존 취소 행은 BUYER로 채웠음, 방어용)
    public OrderItemCancelled.CancelReason cancelReasonOrBuyer() {
        return cancelReason == null ? OrderItemCancelled.CancelReason.BUYER : cancelReason;
    }

    // 취소 흐름(취소 중, 취소 완료, 확인 필요)에 들어간 주문상품
    public boolean isInCancellation() {
        return status == OrderItemStatus.CANCEL_PROCESSING || status == OrderItemStatus.CANCELLED
                || status == OrderItemStatus.REVIEW_REQUIRED;
    }

}
