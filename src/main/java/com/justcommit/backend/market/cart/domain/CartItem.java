package com.justcommit.backend.market.cart.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;

@Entity
@Table(name = "cart_item")
@Getter
public class CartItem extends BaseTimeEntity {

    private Long productId;

    @ManyToOne(fetch = FetchType.LAZY)
    private Cart cart;

    @Column(precision = 19, nullable = false)
    private BigDecimal price;


}
