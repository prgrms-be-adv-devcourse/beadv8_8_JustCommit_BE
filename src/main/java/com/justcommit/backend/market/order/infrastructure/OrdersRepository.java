package com.justcommit.backend.market.order.infrastructure;

import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.order.domain.Orders;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;

public interface OrdersRepository extends JpaRepository<Orders, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Orders> findByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime cutoff);
}
