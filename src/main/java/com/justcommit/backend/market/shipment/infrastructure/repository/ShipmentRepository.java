package com.justcommit.backend.market.shipment.infrastructure.repository;

import com.justcommit.backend.market.shipment.domain.Shipment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {
    Optional<Shipment> findByCourierOrderNo(String courierOrderNo);

    List<Shipment> findBySellerOrderIdIn(List<Long> sellerOrderIds);

    Optional<Shipment> findBySellerOrderId(Long sellerOrderId);
}
