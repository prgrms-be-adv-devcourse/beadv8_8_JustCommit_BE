package com.justcommit.backend.market.cart.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "cart")
@Getter
public class Cart extends BaseTimeEntity {

    @Column(unique = true, nullable = false)
    private Long memberId;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<CartItem> items = new ArrayList<>();


}
