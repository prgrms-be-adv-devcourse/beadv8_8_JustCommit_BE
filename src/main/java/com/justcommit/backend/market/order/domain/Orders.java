package com.justcommit.backend.market.order.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import com.justcommit.backend.market.OrderStatus;
import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
public class Orders extends BaseTimeEntity {

    private Long buyerId;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY, mappedBy = "order")
    private List<SellerOrder> sellerOrders = new ArrayList<>();

    @Column(precision = 19, nullable = false)
    private BigDecimal totalAmount;

    private String recipientName;

    private String recipientPhone;

    private String zipcode;
    private String address1;
    private String address2;

    private Integer itemCount;

    @Enumerated(EnumType.STRING)
    private OrderStatus orderStatus;

}
