package com.justcommit.backend.market.cart.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(
        name = "cart_item",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_cart_item_cart_product",
                columnNames = { "cart_id", "product_id"}
))
@Getter
@NoArgsConstructor
public class CartItem extends BaseTimeEntity {

    @Column(name = "product_id",  nullable = false)
    private Long productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(precision = 19, nullable = false)
    private BigDecimal price;

    public CartItem(Cart cart, Long productId, BigDecimal price) {
        this.cart = cart;
        this.productId = productId;
        this.price = price;
    }
}
