package com.justcommit.backend.market.cart.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "cart")
@NoArgsConstructor
@Getter
public class Cart extends BaseTimeEntity {

    @Column(unique = true, nullable = false)
    private Long memberId;

    @OneToMany(mappedBy = "cart", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    private List<CartItem> items = new ArrayList<>();

    public Cart(Long memberId) {
        this.memberId = memberId;
    }

    public void addItem(CartItem item) {
        this.items.add(item);
    }

}
