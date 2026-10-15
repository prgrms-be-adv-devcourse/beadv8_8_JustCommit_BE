package com.justcommit.backend.market.order.infrastructure;

import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface OrdersItemRepository extends JpaRepository<OrdersItem, Long> {

    @Query("select i.sellerOrder.order.id from OrdersItem i where i.id = :itemId")
    Optional<Long> findOrderIdByItemId(@Param("itemId") Long itemId);

    @Query("select i.sellerOrder.id from OrdersItem i where i.id = :itemId")
    Optional<Long> findSellerOrderIdByItemId(@Param("itemId") Long itemId);

    @Query("""
            select new com.justcommit.backend.market.order.infrastructure.AutoConfirmTarget(o.id, s.id, i.id)
            from OrdersItem i
            join i.sellerOrder s
            join s.order o
            join Shipment sh on sh.sellerOrderId = s.id
            where o.status = :orderStatus
              and i.status = :itemStatus
              and sh.status = :shipmentStatus
              and sh.deliveredAt <= :cutoff
            order by o.id, s.id, i.id
            """)
    List<AutoConfirmTarget> findAutoConfirmTargets(@Param("orderStatus") OrderStatus orderStatus,
                                                   @Param("itemStatus") OrderItemStatus itemStatus,
                                                   @Param("shipmentStatus") ShipmentStatus shipmentStatus,
                                                   @Param("cutoff") LocalDateTime cutoff);

    @Query("select i from OrdersItem i join fetch i.sellerOrder s join fetch s.order where s.order.id in :orderIds order by i.id")
    List<OrdersItem> findByOrderIds(@Param("orderIds") List<Long> orderIds);

    @Query("select i from OrdersItem i join fetch i.sellerOrder s where s.id in :sellerOrderIds order by i.id")
    List<OrdersItem> findBySellerOrderIds(@Param("sellerOrderIds") List<Long> sellerOrderIds);
}
