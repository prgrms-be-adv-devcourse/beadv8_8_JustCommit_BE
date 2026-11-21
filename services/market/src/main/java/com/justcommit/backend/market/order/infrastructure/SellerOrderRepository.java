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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SellerOrderRepository extends JpaRepository<SellerOrder, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SellerOrder s where s.id = :sellerOrderId")
    Optional<SellerOrder> findLockedById(@Param("sellerOrderId") Long sellerOrderId);

    // 잠금 순서(주문 → 판매자 주문)를 지키려고 판매자 주문을 잠그기 전에 주문 id만 읽는다
    @Query("select s.order.id from SellerOrder s where s.id = :sellerOrderId")
    Optional<Long> findOrderIdById(@Param("sellerOrderId") Long sellerOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SellerOrder s where s.order.id = :orderId order by s.id")
    List<SellerOrder> findAllLockedByOrderId(@Param("orderId") Long orderId);

    // 수락 자동 거절 후보 (#285): 결제 완료이고 수락 마감이 지났으며 수락 대기 주문상품이 남은 판매자 주문 id,
    // 오래된 결제부터. paid_at이 없는 판매자 주문(V9 이전 결제분)은 비교에서 빠진다.
    // 잠그지 않는다. 잠금은 판매자 주문마다 SellerOrderDecisionTransactions.autoReject에서 주문 → 판매자 주문 순으로 한다
    @Query("""
            select s.id from SellerOrder s
             where s.order.status = :paid and s.paidAt <= :paidBefore
               and exists (select 1 from OrdersItem i where i.sellerOrder = s and i.status = :acceptPending)
             order by s.paidAt asc, s.id asc
             limit :limit
            """)
    List<Long> findAutoRejectCandidateIds(@Param("paid") OrderStatus paid,
                                          @Param("acceptPending") OrderItemStatus acceptPending,
                                          @Param("paidBefore") LocalDateTime paidBefore,
                                          @Param("limit") int limit);

    // 결제 완료 시각 기록 (#284). 재결제 · 복구로 결제가 확정돼도 확정된 그 시각
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SellerOrder s set s.paidAt = :paidAt, s.updatedAt = :paidAt where s.order.id = :orderId")
    int markPaid(@Param("orderId") Long orderId, @Param("paidAt") LocalDateTime paidAt);

    @EntityGraph(attributePaths = "order")
    Page<SellerOrder> findBySellerId(Long sellerId, Pageable pageable);

    @EntityGraph(attributePaths = "order")
    Optional<SellerOrder> findByIdAndSellerId(Long sellerOrderId, Long sellerId);

    // 판매자 본인 판매자 주문인지 (행을 읽지 않고 확인만). 잠그기 전에 남의 요청을 걸러낸다 (#383)
    boolean existsByIdAndSellerId(Long sellerOrderId, Long sellerId);

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
