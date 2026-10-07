package com.justcommit.backend.market.cart.infrastructure;

import com.justcommit.backend.market.cart.domain.Cart;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartRepository extends JpaRepository<Cart, Long> {
}
