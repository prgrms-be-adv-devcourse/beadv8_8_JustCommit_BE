package com.justcommit.backend.market.order.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import com.justcommit.backend.market.OrderItemStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
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

    public void calculateSettlementBaseAmount() {
        this.settlementBaseAmount = this.items.stream()
                .filter(i->
                        i.getStatus().equals(OrderItemStatus.NORMAL)
                                || i.getStatus().equals(OrderItemStatus.REFUND_REQUESTED)
                                || i.getStatus().equals(OrderItemStatus.CONFIRMED))
                .map(OrdersItem::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void confirmed() {
        this.confirmedAt = LocalDateTime.now();
    }

}
