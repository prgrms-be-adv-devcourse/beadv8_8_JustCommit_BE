package com.justcommit.backend.market.order.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import com.justcommit.backend.market.OrderItemStatus;
import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;

@Entity
@Table(name = "orders_item")
@Getter
public class OrdersItem extends BaseTimeEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    private SellerOrder sellerOrder;

    private Long productId;

    private String productName;

    @Column(precision = 19, nullable = false)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    private OrderItemStatus status;

}
