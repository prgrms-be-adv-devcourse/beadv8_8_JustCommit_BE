package com.justcommit.backend.market.order.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "seller_order")
@Getter
public class SellerOrder extends BaseTimeEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    private Orders order;

    @OneToMany(mappedBy = "sellerOrder", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<OrdersItem> items =  new ArrayList<>();

    private Long sellerId;

    @Column(precision = 19, nullable = false)
    private BigDecimal totalAmount;

    private LocalDateTime confirmedAt;

    @Column(precision = 19, nullable = false)
    private BigDecimal shippingFee;

}
