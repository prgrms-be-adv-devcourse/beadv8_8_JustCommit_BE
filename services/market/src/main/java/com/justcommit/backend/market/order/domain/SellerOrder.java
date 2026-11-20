package com.justcommit.backend.market.order.domain;

import com.justcommit.backend.market.common.entity.BaseTimeEntity;
import com.justcommit.backend.market.OrderItemStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "seller_order",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_seller_order_order_seller",
                columnNames = { "order_id", "seller_id"}
        ))
@Getter
@NoArgsConstructor
public class SellerOrder extends BaseTimeEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id",  nullable = false)
    private Orders order;

    @OneToMany(mappedBy = "sellerOrder", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    private List<OrdersItem> items =  new ArrayList<>();

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(precision = 19, nullable = false)
    private BigDecimal totalAmount;

    @Column(precision = 19, nullable = false)
    private BigDecimal settlementBaseAmount;

    private LocalDateTime confirmedAt;

    @Column(precision = 19, nullable = false)
    private BigDecimal shippingFee;

    // 결제 완료 시각 (재결제면 재결제 완료 시각). 수락 마감 = paidAt + ACCEPT_WINDOW (#284)
    private LocalDateTime paidAt;

    public static final Duration ACCEPT_WINDOW = Duration.ofHours(24);

    public SellerOrder(Orders order, Long sellerId) {
        this.order = order;
        this.sellerId = sellerId;
        this.totalAmount = BigDecimal.ZERO;
        this.settlementBaseAmount = BigDecimal.ZERO;
        this.shippingFee = BigDecimal.ZERO;
    }

    public void addItems(List<OrdersItem> items) {
        this.items.addAll(items);
        this.calculateTotalAmount();
    }

    public void setShippingFee(BigDecimal shippingFee) {
        this.shippingFee = shippingFee;
    }

    private void calculateTotalAmount() {
        this.totalAmount = this.items.stream().map(OrdersItem::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        this.calculateSettlementBaseAmount();
    }

    // 정산 기준액 = 취소되지 않은 주문상품 금액. 수락 대기도 결제된 유효 상품이라 포함한다 (#284).
    // 빼면 결제 직후 판매자 주문이 0원(미정산 대상, 전부 취소와 같은 값)으로 보이고 수락할 때마다 다시 계산해야 한다.
    // 구매 확정은 배송 완료 뒤라 그 시점에 수락 대기 상품은 남아 있지 않다.
    // 반품 진행 중(신청, 배송 중, 환불 중)은 거절되면 구매 확정되므로 포함하고, 반품 완료는 환불됐으므로 뺀다 (#382)
    public void calculateSettlementBaseAmount() {
        this.settlementBaseAmount = this.items.stream()
                .filter(i->
                        i.getStatus().equals(OrderItemStatus.ACCEPT_PENDING)
                                || i.getStatus().equals(OrderItemStatus.NORMAL)
                                || i.getStatus().equals(OrderItemStatus.RETURN_REQUESTED)
                                || i.getStatus().equals(OrderItemStatus.RETURN_IN_TRANSIT)
                                || i.getStatus().equals(OrderItemStatus.RETURN_PROCESSING)
                                || i.getStatus().equals(OrderItemStatus.CONFIRMED))
                .map(OrdersItem::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void confirmed() {
        this.confirmedAt = LocalDateTime.now();
    }

    // 수락 마감 시각. 수락 대기 주문상품이 남아 있을 때만 값이 있다
    public LocalDateTime acceptDeadline() {
        if (paidAt == null || items.stream().noneMatch(i -> i.getStatus() == OrderItemStatus.ACCEPT_PENDING)) {
            return null;
        }
        return paidAt.plus(ACCEPT_WINDOW);
    }

}
