package com.justcommit.backend.payment.infrastructure.repository;

import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.WalletTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {
    Page<WalletTransaction> findByWallet_Id(Long walletId, Pageable pageable);

    boolean existsByWallet_IdAndRefTypeAndRefId(Long walletId, RefType refType, Long refId);

    Optional<WalletTransaction> findByWallet_IdAndRefTypeAndRefId(Long walletId, RefType refType, Long refId);

    Page<WalletTransaction> findByWallet_IdAndRefType(Long walletId, RefType refType, Pageable pageable);
}
