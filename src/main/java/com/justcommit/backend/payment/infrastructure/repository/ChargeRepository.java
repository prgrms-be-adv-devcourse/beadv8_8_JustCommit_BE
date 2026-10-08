package com.justcommit.backend.payment.infrastructure.repository;

import com.justcommit.backend.payment.domain.Charge;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChargeRepository extends JpaRepository<Charge, Long> {
}
