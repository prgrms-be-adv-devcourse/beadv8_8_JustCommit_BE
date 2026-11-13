package com.justcommit.backend.market.order.infrastructure;

import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.order.domain.SellerOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SellerOrderRepository extends JpaRepository<SellerOrder, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SellerOrder s where s.id = :sellerOrderId")
    Optional<SellerOrder> findLockedById(@Param("sellerOrderId") Long sellerOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SellerOrder s where s.order.id = :orderId order by s.id")
    List<SellerOrder> findAllLockedByOrderId(@Param("orderId") Long orderId);

    @EntityGraph(attributePaths = "order")
    Page<SellerOrder> findBySellerId(Long sellerId, Pageable pageable);

    @EntityGraph(attributePaths = "order")
    Optional<SellerOrder> findByIdAndSellerId(Long sellerOrderId, Long sellerId);

    List<SellerOrder> findByOrder_Id(Long orderId);

    List<SellerOrder> findBySellerId(Long sellerId);

    List<SellerOrder> findByOrderStatusAndConfirmedAtIsNullAndSettlementBaseAmountGreaterThan(OrderStatus status, BigDecimal amount);

    // 탈퇴 확인: 판매자로 진행 중인 판매자 주문 수. 주문상품이 여러 개여도 판매자 주문 하나로 센다.
    @Query("""
            select count(distinct s.id) from SellerOrder s
            join s.order o
            join s.items i
            where s.sellerId = :sellerId
              and o.status in :orderStatuses
              and i.status in :itemStatuses
            """)
    long countActiveBySellerId(@Param("sellerId") Long sellerId,
                               @Param("orderStatuses") Collection<OrderStatus> orderStatuses,
                               @Param("itemStatuses") Collection<OrderItemStatus> itemStatuses);
}
