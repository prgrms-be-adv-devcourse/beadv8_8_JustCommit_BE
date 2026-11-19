package com.justcommit.backend.payment.infrastructure.repository;

import com.justcommit.backend.payment.domain.CashOut;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface CashOutRepository extends JpaRepository<CashOut, Long> {

    Optional<CashOut> findByWalletIdAndIdempotencyKey(Long walletId, String idempotencyKey);

    Page<CashOut> findByWalletId(Long walletId, Pageable pageable);

    // 지급 성공: PENDING일 때만 COMPLETED로 (이미 바뀐 건은 0건)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update CashOut c
               set c.status = com.justcommit.backend.payment.domain.CashOutStatus.COMPLETED,
                   c.completedAt = :completedAt,
                   c.updatedAt = :completedAt
             where c.id = :id
               and c.status = com.justcommit.backend.payment.domain.CashOutStatus.PENDING
            """)
    int markCompleted(@Param("id") Long id, @Param("completedAt") LocalDateTime completedAt);
}
