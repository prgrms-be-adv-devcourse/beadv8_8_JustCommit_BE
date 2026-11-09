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

    // 스케줄러 후보 조회라 잠그지 않는다. 잠금은 주문마다 findByIdForUpdateSkipLocked로 한다.
    List<Orders> findTop100ByStatusAndCreatedAtBeforeOrderByIdAsc(OrderStatus status, LocalDateTime cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Orders o where o.id = :orderId")
    Optional<Orders> findLockedById(@Param("orderId") Long orderId);

    // 스케줄러용: 다른 파드 · 요청이 잡은 주문은 기다리지 않고 빈 값을 받아 건너뛴다.
    @Query(value = "select * from orders where id = :orderId for update skip locked", nativeQuery = true)
    Optional<Orders> findByIdForUpdateSkipLocked(@Param("orderId") Long orderId);
}
