package com.justcommit.backend.market.order.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import com.justcommit.backend.market.OrderItemStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

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

    public OrdersItem(SellerOrder sellerOrder, Long productId, String productName, BigDecimal price) {
        this.sellerOrder = sellerOrder;
        this.productId = productId;
        this.productName = productName;
        this.price = price;
        this.status = OrderItemStatus.NORMAL;
    }

}
