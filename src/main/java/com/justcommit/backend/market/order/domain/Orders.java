package com.justcommit.backend.market.order.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import com.justcommit.backend.market.OrderStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@NoArgsConstructor
@Getter
public class Orders extends BaseTimeEntity {

    @Column(unique = true,  nullable = false, length = 30)
    private String orderNo;

    @Column(nullable = false)
    private Long buyerId;

    @OneToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY, mappedBy = "order")
    private List<SellerOrder> sellerOrders = new ArrayList<>();

    @Column(precision = 19, nullable = false)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private String recipientName;

    @Column(nullable = false)
    private String recipientPhone;

    @Column(nullable = false)
    private String zipcode;

    @Column(nullable = false)
    private String shipAddress1;

    @Column(nullable = false)
    private String shipAddress2;

    @Column(nullable = false)
    private Integer itemCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    public Orders(String orderNo, Long buyerId, String recipientName, String recipientPhone, String zipcode, String shipAddress1, String shipAddress2, Integer itemCount) {
        this.orderNo = orderNo;
        this.buyerId = buyerId;
        this.recipientName = recipientName;
        this.recipientPhone = recipientPhone;
        this.zipcode = zipcode;
        this.shipAddress1 = shipAddress1;
        this.shipAddress2 = shipAddress2;
        this.itemCount = itemCount;
        this.totalAmount = BigDecimal.ZERO;
        this.status = OrderStatus.PAYMENT_PENDING;
    }

    public void addSellerOrders(List<SellerOrder> sellerOrders) {
        this.sellerOrders.addAll(sellerOrders);
        this.calculateTotalAmount();
    }

    public void calculateTotalAmount() {
        this.totalAmount = this.sellerOrders.stream().map(o -> o.getTotalAmount().add(o.getShippingFee())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
