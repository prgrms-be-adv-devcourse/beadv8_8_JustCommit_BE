package com.justcommit.backend.settlement.infrastructure.repository;

import com.justcommit.backend.settlement.domain.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findBySellerOrderId(Long sellerOrderId);
}