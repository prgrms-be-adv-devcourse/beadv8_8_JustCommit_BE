package com.justcommit.backend.market.order.infrastructure;

import com.justcommit.backend.market.order.domain.SellerOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;

public interface SellerOrderRepository extends JpaRepository<SellerOrder, Long> {
    @EntityGraph(attributePaths = "order")
    Page<SellerOrder> findBySellerId(Long sellerId, Pageable pageable);

    @EntityGraph(attributePaths = "order")
    Optional<SellerOrder> findByIdAndSellerId(Long sellerOrderId, Long sellerId);

    List<SellerOrder> findByOrder_Id(Long orderId);

    List<SellerOrder> findBySellerId(Long sellerId);
}
