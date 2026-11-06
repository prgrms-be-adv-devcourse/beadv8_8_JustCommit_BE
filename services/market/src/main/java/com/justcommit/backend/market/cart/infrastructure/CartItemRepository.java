package com.justcommit.backend.market.cart.infrastructure;

import com.justcommit.backend.market.cart.domain.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
}
