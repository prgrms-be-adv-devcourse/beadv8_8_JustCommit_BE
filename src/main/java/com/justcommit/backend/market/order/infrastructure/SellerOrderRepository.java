package com.justcommit.backend.market.order.infrastructure;

import com.justcommit.backend.market.order.domain.SellerOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerOrderRepository extends JpaRepository<SellerOrder, Long> {
}
