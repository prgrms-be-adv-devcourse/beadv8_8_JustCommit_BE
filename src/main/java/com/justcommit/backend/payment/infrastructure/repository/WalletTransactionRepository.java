package com.justcommit.backend.payment.infrastructure.repository;

import com.justcommit.backend.payment.domain.WalletTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {
    Page<WalletTransaction> findByWallet_Id(Long walletId, Pageable pageable);
}
