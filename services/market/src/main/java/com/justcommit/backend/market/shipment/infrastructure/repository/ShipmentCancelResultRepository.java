package com.justcommit.backend.market.shipment.infrastructure.repository;

import com.justcommit.backend.market.shipment.domain.ShipmentCancelResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShipmentCancelResultRepository extends JpaRepository<ShipmentCancelResult, Long> {
    Optional<ShipmentCancelResult> findByShipmentId(Long shipmentId);

    // 주문 Saga 후보: 처리 전 결과 id (부분 인덱스 사용). 잠그지 않는다
    @Query("select r.id from ShipmentCancelResult r where r.processedAt is null order by r.id limit :limit")
    List<Long> findUnprocessedIds(@Param("limit") int limit);

    // 다른 파드가 잡은 결과는 기다리지 않고 건너뛴다
    @Query(value = "select * from shipment_cancel_result where id = :id and processed_at is null for update skip locked",
            nativeQuery = true)
    Optional<ShipmentCancelResult> findUnprocessedForUpdateSkipLocked(@Param("id") Long id);
}
