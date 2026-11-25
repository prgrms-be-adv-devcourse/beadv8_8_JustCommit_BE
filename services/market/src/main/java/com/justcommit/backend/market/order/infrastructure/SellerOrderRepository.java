package com.justcommit.backend.market.order.infrastructure;

import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderReviewTarget;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.ReviewableSellerOrder;
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

    // 판매 내역 (#599): 결제 완료 이후 주문(PAID · CANCELLED · COMPLETED)만. CANCELLED는 PAID에서만 간다.
    // 결제 대기 · 처리 중 · 실패 · 만료 · 확인 필요 주문은 판매자에게 보이지 않는다
    @EntityGraph(attributePaths = "order")
    @Query("""
            select s from SellerOrder s
             where s.sellerId = :sellerId
               and s.order.status in (com.justcommit.backend.market.OrderStatus.PAID,
                                      com.justcommit.backend.market.OrderStatus.CANCELLED,
                                      com.justcommit.backend.market.OrderStatus.COMPLETED)
            """)
    Page<SellerOrder> findBySellerId(@Param("sellerId") Long sellerId, Pageable pageable);

    // 판매 상세 (#599): 목록과 같은 기준. 결제 완료 이후가 아니면 없는 것으로 본다(404)
    @EntityGraph(attributePaths = "order")
    @Query("""
            select s from SellerOrder s
             where s.id = :sellerOrderId and s.sellerId = :sellerId
               and s.order.status in (com.justcommit.backend.market.OrderStatus.PAID,
                                      com.justcommit.backend.market.OrderStatus.CANCELLED,
                                      com.justcommit.backend.market.OrderStatus.COMPLETED)
            """)
    Optional<SellerOrder> findByIdAndSellerId(@Param("sellerOrderId") Long sellerOrderId,
                                              @Param("sellerId") Long sellerId);

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

    // 리뷰 작성 후보 판매자 주문 (#585): 구매자의 주문 · confirmedAt이 confirmedAfter 이후 · 구매 확정 주문상품이 있음.
    // confirmedAfter는 마이크로초로 반올림돼 전달되므로 경계(>=)까지 넓게 고르고, 작성 기간 판단은 review가 Java로 한다
    @Query("""
            select new com.justcommit.backend.market.ReviewableSellerOrder(s.id, o.id, s.sellerId, o.createdAt, s.confirmedAt)
            from SellerOrder s join s.order o
            where o.buyerId = :buyerId
              and s.confirmedAt >= :confirmedAfter
              and exists (select 1 from OrdersItem i where i.sellerOrder = s and i.status = :status)
            """)
    List<ReviewableSellerOrder> findReviewable(@Param("buyerId") Long buyerId,
                                               @Param("confirmedAfter") LocalDateTime confirmedAfter,
                                               @Param("status") OrderItemStatus status);

    // 주문 상세의 판매자별 리뷰 상태용 (#644): 구매자 본인의 주문만, 판매자 주문 id 순.
    // 남의 주문이면 없는 주문과 같이 빈 목록
    @Query("""
            select new com.justcommit.backend.market.OrderReviewTarget(s.id, s.sellerId, s.confirmedAt,
                case when exists (select 1 from OrdersItem i where i.sellerOrder = s and i.status = :confirmed)
                    then true else false end,
                case when not exists (select 1 from OrdersItem i where i.sellerOrder = s and i.status <> :cancelled)
                    then true else false end)
            from SellerOrder s join s.order o
            where o.id = :orderId and o.buyerId = :buyerId
            order by s.id
            """)
    List<OrderReviewTarget> findOrderReviewTargets(@Param("buyerId") Long buyerId,
                                                   @Param("orderId") Long orderId,
                                                   @Param("confirmed") OrderItemStatus confirmed,
                                                   @Param("cancelled") OrderItemStatus cancelled);
}
