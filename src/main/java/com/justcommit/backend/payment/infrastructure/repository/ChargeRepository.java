package com.justcommit.backend.payment.infrastructure.repository;

import com.justcommit.backend.payment.domain.Charge;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface ChargeRepository extends JpaRepository<Charge, Long> {

    Optional<Charge> findByIdAndWallet_MemberId(Long id, Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Charge c where c.id = :id and c.wallet.memberId = :memberId")
    Optional<Charge> findByIdAndMemberIdForUpdate(@Param("id") Long id, @Param("memberId") Long memberId);

}
