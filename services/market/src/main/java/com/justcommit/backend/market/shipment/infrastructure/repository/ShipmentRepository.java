package com.justcommit.backend.market.shipment.infrastructure.repository;

import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentDirection;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {
    Optional<Shipment> findByCourierOrderNo(String courierOrderNo);

    // 판매자 주문으로 배송을 찾을 때는 방향을 꼭 고른다. 반품 배송은 판매자 주문당 여러 개라 방향 없이 찾으면 결과가 여러 건이 된다 (#382)
    List<Shipment> findBySellerOrderIdInAndDirection(List<Long> sellerOrderIds, ShipmentDirection direction);

    Optional<Shipment> findBySellerOrderIdAndDirection(Long sellerOrderId, ShipmentDirection direction);

    // 오래 남은 배송 지표: 만든 지 기준 시각이 지났는데 아직 예약 안 된(READY) 배송 수
    long countByStatusAndCreatedAtBefore(ShipmentStatus status, LocalDateTime createdBefore);

    // 확인 필요 배송 지표 (#671): 택배사가 예약을 거절해 멈춘 배송 수
    long countByStatus(ShipmentStatus status);

    // 오래 남은 배송 지표: 취소를 누른 지 기준 시각이 지났는데 아직 택배사 결과를 못 받은(CANCEL_PENDING) 배송 수
    long countByStatusAndCancelRequestedAtBefore(ShipmentStatus status, LocalDateTime requestedBefore);

    // 취소 요청: 행을 잠그고 읽어 같은 배송의 동시 취소 · 웹훅이 차례로 반영되게 한다 (잠금 순서: 주문 → 판매자 주문 → 배송).
    // 구매자 취소는 가는 길 배송만 대상이다 (반품 택배는 취소할 수 없음)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s where s.sellerOrderId = :sellerOrderId and s.direction = :direction")
    Optional<Shipment> findForUpdateBySellerOrderIdAndDirection(@Param("sellerOrderId") Long sellerOrderId,
                                                                @Param("direction") ShipmentDirection direction);

    // 예약 큐: 시도할 때가 된 READY 배송 한 건을 잠그고 가져온다.
    // 다른 파드가 이미 잠근 행은 기다리지 않고 건너뛴다(SKIP LOCKED). 반품 배송도 READY면 같이 집는다.
    @Query(value = """
            select * from shipment
            where status = 'READY'
              and (next_attempt_at is null or next_attempt_at <= :now)
            order by next_attempt_at nulls first, id
            limit 1
            for update skip locked
            """, nativeQuery = true)
    Optional<Shipment> findNextBookable(@Param("now") LocalDateTime now);

    // 택배 취소 큐: 시도할 때가 된 CANCEL_PENDING 배송 한 건을 잠그고 가져온다 (다른 파드가 잠근 행은 건너뜀)
    @Query(value = """
            select * from shipment
            where status = 'CANCEL_PENDING'
              and (next_attempt_at is null or next_attempt_at <= :now)
            order by next_attempt_at nulls first, id
            limit 1
            for update skip locked
            """, nativeQuery = true)
    Optional<Shipment> findNextCancellable(@Param("now") LocalDateTime now);

}
