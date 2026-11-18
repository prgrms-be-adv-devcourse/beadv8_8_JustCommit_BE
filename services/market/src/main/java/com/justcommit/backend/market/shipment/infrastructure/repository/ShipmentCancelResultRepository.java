package com.justcommit.backend.market.shipment.infrastructure.repository;

import com.justcommit.backend.market.shipment.domain.ShipmentCancelResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShipmentCancelResultRepository extends JpaRepository<ShipmentCancelResult, Long> {
    Optional<ShipmentCancelResult> findByShipmentId(Long shipmentId);
}
