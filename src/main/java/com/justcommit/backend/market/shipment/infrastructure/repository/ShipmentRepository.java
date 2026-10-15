package com.justcommit.backend.market.shipment.infrastructure.repository;

import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {
    Optional<Shipment> findByCourierOrderNo(String courierOrderNo);

    List<Shipment> findBySellerOrderIdIn(List<Long> sellerOrderIds);

    Optional<Shipment> findBySellerOrderId(Long sellerOrderId);

    List<Shipment> findTop100ByStatusAndCreatedAtBeforeOrderByIdAsc(ShipmentStatus status, LocalDateTime createdAt);
}
