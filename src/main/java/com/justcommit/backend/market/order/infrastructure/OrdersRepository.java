package com.justcommit.backend.market.order.infrastructure;

import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.order.domain.Orders;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrdersRepository extends JpaRepository<Orders, Long> {
    Page<Orders> findByBuyerId(Long buyerId, Pageable pageable);

    Optional<Orders> findByIdAndBuyerId(Long orderId, Long buyerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Orders> findByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Orders o where o.id = :orderId")
    Optional<Orders> findLockedById(@Param("orderId") Long orderId);
}
