package com.justcommit.backend.market.order.infrastructure;

import com.justcommit.backend.market.order.domain.OrdersItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrdersItemRepository extends JpaRepository<OrdersItem, Long> {

    @Query("select i.sellerOrder.order.id from OrdersItem i where i.id = :itemId")
    Optional<Long> findOrderIdByItemId(@Param("itemId") Long itemId);

    @Query("select i.sellerOrder.id from OrdersItem i where i.id = :itemId")
    Optional<Long> findSellerOrderIdByItemId(@Param("itemId") Long itemId);

    @Query("select i from OrdersItem i join fetch i.sellerOrder s join fetch s.order where s.order.id in :orderIds order by i.id")
    List<OrdersItem> findByOrderIds(@Param("orderIds") List<Long> orderIds);

    @Query("select i from OrdersItem i join fetch i.sellerOrder s where s.id in :sellerOrderIds order by i.id")
    List<OrdersItem> findBySellerOrderIds(@Param("sellerOrderIds") List<Long> sellerOrderIds);
}
