package com.justcommit.backend.market.shipment.infrastructure.repository;

import com.justcommit.backend.market.shipment.domain.Shipment;
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

    List<Shipment> findBySellerOrderIdIn(List<Long> sellerOrderIds);

    Optional<Shipment> findBySellerOrderId(Long sellerOrderId);

    // 취소 요청: 행을 잠그고 읽어 같은 배송의 동시 취소 · 웹훅이 차례로 반영되게 한다 (잠금 순서: 주문 → 판매자 주문 → 배송)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s where s.sellerOrderId = :sellerOrderId")
    Optional<Shipment> findForUpdateBySellerOrderId(@Param("sellerOrderId") Long sellerOrderId);

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
