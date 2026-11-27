package com.justcommit.backend.market.order.infrastructure;

import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.order.domain.ReturnRequest;
import com.justcommit.backend.market.order.domain.ReturnRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, Long> {

    Optional<ReturnRequest> findByOrderItemId(Long orderItemId);

    // 반품 신청 행을 읽지 않고 주문상품 id만 꺼낸다. 행은 주문 → 판매자 주문을 잠근 뒤에 읽어야 최신 상태다
    @Query("select r.orderItemId from ReturnRequest r where r.id = :id")
    Optional<Long> findOrderItemIdById(@Param("id") Long id);

    // 판매 목록, 상세의 반품 정보 (#383). 판매자 주문 id 목록으로 한 번에 읽는다 (N+1 없음)
    List<ReturnRequest> findBySellerOrderIdIn(Collection<Long> sellerOrderIds);

    // 반품 자동 승인 후보 (#383): 응답 전이고 신청 시각이 기준 시각(지금 - 응답 기간) 이하인 반품 신청 id, 오래된 신청부터.
    // 잠그지 않는다. 잠금은 건마다 OrderReturnService.autoApprove에서 주문 → 판매자 주문 순으로 한다
    @Query("""
            select r.id from ReturnRequest r
             where r.status = :requested and r.requestedAt <= :requestedBefore
             order by r.requestedAt asc, r.id asc
             limit :limit
            """)
    List<Long> findAutoApproveCandidateIds(@Param("requested") ReturnRequestStatus requested,
                                           @Param("requestedBefore") LocalDateTime requestedBefore,
                                           @Param("limit") int limit);

    // 반품 도착 처리 후보 (#384): 승인됐고 반품 배송이 연결됐으며 주문상품이 아직 반품 배송 중인 반품 신청, 오래된 신청부터.
    // 주문상품 상태로 걸러, 이미 도착 처리된 건(환불 중, 확인 필요)이 다시 집혀 새 도착을 밀어내지 않게 한다.
    // 잠그지 않는다. 잠금은 건마다 OrderReturnRefundTransactions.markArrived에서 주문 → 판매자 주문 순으로 한다
    @Query("""
            select r from ReturnRequest r, OrdersItem i
             where i.id = r.orderItemId
               and r.status = :approved and r.shipmentId is not null
               and i.status = :inTransit
             order by r.id asc
             limit :limit
            """)
    List<ReturnRequest> findArrivalCandidates(@Param("approved") ReturnRequestStatus approved,
                                              @Param("inTransit") OrderItemStatus inTransit,
                                              @Param("limit") int limit);

    // 판매자 주문의 반품 배송비 합 (판매자 부담금). 해당 상태의 반품이 없으면 0
    @Query("""
            select coalesce(sum(r.returnShippingFee), 0) from ReturnRequest r
             where r.sellerOrderId = :sellerOrderId and r.status = :status
            """)
    BigDecimal sumReturnShippingFee(@Param("sellerOrderId") Long sellerOrderId,
                                    @Param("status") ReturnRequestStatus status);

    // 판매자 주문 여러 개의 반품 배송비 합을 한 번에 (#657, N+1 없음). 해당 상태의 반품이 없는 판매자 주문은 결과에 없다
    @Query("""
            select new com.justcommit.backend.market.order.infrastructure.ReturnShippingFeeSum(
                r.sellerOrderId, sum(r.returnShippingFee))
            from ReturnRequest r
            where r.sellerOrderId in :sellerOrderIds and r.status = :status
            group by r.sellerOrderId
            """)
    List<ReturnShippingFeeSum> sumReturnShippingFeeBySellerOrderIds(
            @Param("sellerOrderIds") Collection<Long> sellerOrderIds, @Param("status") ReturnRequestStatus status);
}
