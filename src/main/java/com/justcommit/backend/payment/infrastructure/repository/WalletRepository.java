package com.justcommit.backend.payment.infrastructure.repository;

import com.justcommit.backend.payment.domain.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {
    Optional<Wallet> findByMemberId(Long memberId);
}
