package com.justcommit.backend.payment.infrastructure.repository;

import com.justcommit.backend.payment.domain.Charge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChargeRepository extends JpaRepository<Charge, Long> {
    Optional<Charge> findByIdAndWallet_MemberId(Long id, Long memberId);
}
