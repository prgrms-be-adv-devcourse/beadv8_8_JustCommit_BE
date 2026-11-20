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
import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders_item")
@NoArgsConstructor
@Getter
public class OrdersItem extends BaseTimeEntity {

    // 반품 기간: 가는 길 배송 완료 시각부터 이 시간 안에 반품을 신청할 수 있고, 지나면 자동 구매 확정한다 (#382).
    // 두 판정이 같은 값, 같은 경계를 쓰므로 한 시각에 둘 중 하나만 가능하다
    public static final Duration RETURN_PERIOD = Duration.ofDays(3);

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

    // 반품 기간 안인지: 지금 < 배송 완료 + 반품 기간. 반품 기간이 끝났으면(같거나 지남) 자동 구매 확정 대상이다
    public static boolean isWithinReturnPeriod(LocalDateTime deliveredAt, LocalDateTime now) {
        return deliveredAt.isAfter(now.minus(RETURN_PERIOD));
    }

    // 반품 신청: 일반 → 반품 신청 (#382). 호출 전에 주문 → 판매자 주문을 잠그고 상태를 확인한다
    public void requestReturn() {
        if (this.status != OrderItemStatus.NORMAL) {
            throw new IllegalStateException("일반 상태의 주문 상품만 반품을 신청할 수 있습니다. 현재 상태: " + this.status);
        }
        this.status = OrderItemStatus.RETURN_REQUESTED;
    }

    // 반품 승인(판매자, 자동): 반품 신청 → 반품 배송 중 (#383). 호출 전에 주문 → 판매자 주문을 잠그고 상태를 확인한다
    public void approveReturn() {
        requireReturnRequested();
        this.status = OrderItemStatus.RETURN_IN_TRANSIT;
    }

    // 반품 거절: 반품 신청 → 바로 구매 확정 (#383). confirm()은 일반 상태만 받으므로 따로 둔다
    public void rejectReturn() {
        requireReturnRequested();
        this.status = OrderItemStatus.CONFIRMED;
    }

    private void requireReturnRequested() {
        if (this.status != OrderItemStatus.RETURN_REQUESTED) {
            throw new IllegalStateException("반품 신청 상태의 주문 상품만 승인, 거절할 수 있습니다. 현재 상태: " + this.status);
        }
    }

    // 반품 흐름(신청, 배송 중, 환불 중, 완료)에 들어간 주문상품
    public boolean isInReturn() {
        return status == OrderItemStatus.RETURN_REQUESTED || status == OrderItemStatus.RETURN_IN_TRANSIT
                || status == OrderItemStatus.RETURN_PROCESSING || status == OrderItemStatus.RETURNED;
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
