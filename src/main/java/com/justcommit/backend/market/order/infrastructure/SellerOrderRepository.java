package com.justcommit.backend.market.order.infrastructure;

import com.justcommit.backend.market.order.domain.SellerOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SellerOrderRepository extends JpaRepository<SellerOrder, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SellerOrder s where s.id = :sellerOrderId")
    Optional<SellerOrder> findLockedById(@Param("sellerOrderId") Long sellerOrderId);

    @EntityGraph(attributePaths = "order")
    Page<SellerOrder> findBySellerId(Long sellerId, Pageable pageable);

    @EntityGraph(attributePaths = "order")
    Optional<SellerOrder> findByIdAndSellerId(Long sellerOrderId, Long sellerId);

    List<SellerOrder> findByOrder_Id(Long orderId);

    List<SellerOrder> findBySellerId(Long sellerId);
}
