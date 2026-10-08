package com.justcommit.backend.market.cart.infrastructure;

import com.justcommit.backend.market.cart.domain.Cart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findByMemberId(long memberId);

}
