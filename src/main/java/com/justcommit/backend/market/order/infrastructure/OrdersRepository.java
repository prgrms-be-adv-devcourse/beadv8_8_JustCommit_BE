package com.justcommit.backend.market.order.infrastructure;

import com.justcommit.backend.market.order.domain.Orders;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrdersRepository extends JpaRepository<Orders, Long> {
}
