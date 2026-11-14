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

    // TX2에서 지갑을 먼저 잠그기 위해 Charge 엔티티를 불러오지 않고 회원 ID만 조회
    @Query("select c.wallet.memberId from Charge c where c.id = :id")
    Optional<Long> findMemberIdById(@Param("id") Long id);
}
