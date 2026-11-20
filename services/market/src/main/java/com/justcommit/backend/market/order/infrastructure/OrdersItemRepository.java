package com.justcommit.backend.market.order.infrastructure;

import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.shipment.domain.ShipmentDirection;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
            join Shipment sh on sh.sellerOrderId = s.id and sh.direction = :outbound
            where o.status = :orderStatus
              and i.status = :itemStatus
              and sh.status = :shipmentStatus
              and sh.deliveredAt <= :cutoff
            order by o.id, s.id, i.id
            """)
    List<AutoConfirmTarget> findAutoConfirmTargets(@Param("orderStatus") OrderStatus orderStatus,
                                                   @Param("itemStatus") OrderItemStatus itemStatus,
                                                   @Param("shipmentStatus") ShipmentStatus shipmentStatus,
                                                   @Param("cutoff") LocalDateTime cutoff,
                                                   @Param("outbound") ShipmentDirection outbound);

    // ===== 취소 중 주문상품의 환불 (#282). 상태 전환은 모두 「취소 중일 때만」 조건부 UPDATE라 두 파드, 늦은 응답이 겹쳐도 한 번만 반영된다

    // Saga 복구 후보: 취소 중이고 마지막 환불 시도에서 유예가 지난 주문상품 id, 오래된 시도부터 (부분 인덱스 사용).
    // attempted_at이 비어 있으면(택배 취소 결과 대기) 집지 않는다. 잠그지 않는다
    @Query("""
            select i.id from OrdersItem i
             where i.status = :processing and i.attemptedAt <= :attemptedBefore
             order by i.attemptedAt asc, i.id asc
             limit :limit
            """)
    List<Long> findRefundRecoveryCandidateIds(@Param("processing") OrderItemStatus processing,
                                              @Param("attemptedBefore") LocalDateTime attemptedBefore,
                                              @Param("limit") int limit);

    // 반품 환불 첫 시도 후보 (#384): 반품 환불 중이고 환불 시도 기록이 비어 있는(도착 처리만 된) 주문상품 id.
    // 잠그지 않는다. 선점은 startRefund 조건부 UPDATE로 한다 (재시도 후보는 findRefundRecoveryCandidateIds)
    @Query("""
            select i.id from OrdersItem i
             where i.status = :processing and i.attemptedAt is null
             order by i.id asc
             limit :limit
            """)
    List<Long> findRefundNotStartedIds(@Param("processing") OrderItemStatus processing,
                                       @Param("limit") int limit);

    // 스케줄러용: 다른 파드가 잡은 주문상품은 기다리지 않고 빈 값을 받아 건너뛴다
    @Query(value = "select * from orders_item where id = :itemId for update skip locked", nativeQuery = true)
    Optional<OrdersItem> findByIdForUpdateSkipLocked(@Param("itemId") Long itemId);

    // 판매자 주문에서 택배 취소 결과를 기다리던 취소 중 주문상품 (attempted_at 없음)
    @Query("""
            select i from OrdersItem i
             where i.sellerOrder.id = :sellerOrderId and i.status = :processing and i.attemptedAt is null
             order by i.id
            """)
    List<OrdersItem> findAwaitingCourierCancel(@Param("sellerOrderId") Long sellerOrderId,
                                               @Param("processing") OrderItemStatus processing);

    // 택배 취소됨: 결과를 기다리던 주문상품의 환불 시도를 시작한다 (첫 환불 호출이라 횟수는 세지 않음)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OrdersItem i set i.attemptedAt = :now, i.updatedAt = :now
             where i.id in :itemIds and i.status = :processing and i.attemptedAt is null
            """)
    int startRefund(@Param("itemIds") List<Long> itemIds,
                    @Param("now") LocalDateTime now,
                    @Param("processing") OrderItemStatus processing);

    // 택배 취소 불가: 결과를 기다리던 주문상품을 정상으로 되돌린다 (환불하지 않음)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OrdersItem i set i.status = :normal, i.updatedAt = :now
             where i.sellerOrder.id = :sellerOrderId and i.status = :processing and i.attemptedAt is null
            """)
    int revertAwaitingCourierCancel(@Param("sellerOrderId") Long sellerOrderId,
                                    @Param("now") LocalDateTime now,
                                    @Param("processing") OrderItemStatus processing,
                                    @Param("normal") OrderItemStatus normal);

    // 복구 선점: 횟수 +1, 시도 시각 갱신
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OrdersItem i set i.attemptCount = i.attemptCount + 1, i.attemptedAt = :now, i.updatedAt = :now
             where i.id = :itemId and i.status = :processing
            """)
    int claimRefundRetry(@Param("itemId") Long itemId,
                         @Param("now") LocalDateTime now,
                         @Param("processing") OrderItemStatus processing);

    // 환불 결과 반영: 취소 중일 때만 to(CANCELLED, REVIEW_REQUIRED, 결과 불명이면 CANCEL_PROCESSING 그대로)로 바꾼다
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OrdersItem i set i.status = :to, i.lastError = :lastError, i.updatedAt = :now
             where i.id = :itemId and i.status = :processing
            """)
    int applyRefund(@Param("itemId") Long itemId,
                    @Param("to") OrderItemStatus to,
                    @Param("lastError") String lastError,
                    @Param("now") LocalDateTime now,
                    @Param("processing") OrderItemStatus processing);

    // 결제 완료: 그 주문의 일반 주문상품을 수락 대기로 바꾼다 (#284). 결제 결과 반영과 같은 트랜잭션
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OrdersItem i set i.status = :acceptPending, i.updatedAt = :now
             where i.sellerOrder.id in (select s.id from SellerOrder s where s.order.id = :orderId)
               and i.status = :normal
            """)
    int markAcceptPending(@Param("orderId") Long orderId,
                          @Param("now") LocalDateTime now,
                          @Param("normal") OrderItemStatus normal,
                          @Param("acceptPending") OrderItemStatus acceptPending);

    @Query("select i from OrdersItem i join fetch i.sellerOrder s join fetch s.order where s.order.id in :orderIds order by i.id")
    List<OrdersItem> findByOrderIds(@Param("orderIds") List<Long> orderIds);

    @Query("select i from OrdersItem i join fetch i.sellerOrder s where s.id in :sellerOrderIds order by i.id")
    List<OrdersItem> findBySellerOrderIds(@Param("sellerOrderIds") List<Long> sellerOrderIds);

    // 장바구니 추천 요청용 최근 구매 확정 상품 ID. 기준 시각은 판매자 주문의 confirmedAt (주문상품별 확정 시각이 없음,
    // 판매자 주문의 상품이 모두 확정되기 전의 CONFIRMED 주문상품은 빠짐 → docs/reference/internal-apis.md)
    @Query("""
            select i.productId
            from OrdersItem i
            join i.sellerOrder s
            join s.order o
            where o.buyerId = :buyerId
              and i.status = :status
              and s.confirmedAt >= :since
            order by s.confirmedAt desc, i.id desc
            """)
    List<Long> findRecentProductIdsByBuyer(@Param("buyerId") Long buyerId,
                                           @Param("status") OrderItemStatus status,
                                           @Param("since") LocalDateTime since,
                                           Pageable pageable);
}
