package com.justcommit.backend.market.shipment.infrastructure.repository;

import com.justcommit.backend.market.shipment.domain.ShippingFeeTable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShippingFeeTableRepository extends JpaRepository<ShippingFeeTable, Long> {

    // 지금 쓰는 요금표 = 가장 높은 버전
    Optional<ShippingFeeTable> findTopByOrderByVersionDesc();
}
