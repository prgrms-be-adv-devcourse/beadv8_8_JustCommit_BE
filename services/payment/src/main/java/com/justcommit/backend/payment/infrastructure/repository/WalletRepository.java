package com.justcommit.backend.payment.infrastructure.repository;

import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {
    Optional<Wallet> findByMemberId(Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.memberId = :memberId")
    Optional<Wallet> findByMemberIdForUpdate(@Param("memberId") Long memberId);

    // 시스템 지갑(HOLDING · REVENUE)만 조회한다. MEMBER는 여러 개라 쓰지 않는다
    Optional<Wallet> findByWalletType(WalletType walletType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.walletType = :walletType")
    Optional<Wallet> findByWalletTypeForUpdate(@Param("walletType") WalletType walletType);

}
