package com.justcommit.backend.payment.infrastructure.repository;

import com.justcommit.backend.payment.domain.Charge;
import com.justcommit.backend.payment.domain.ChargeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

public interface ChargeRepository extends JpaRepository<Charge, Long> {

    Optional<Charge> findByIdAndWallet_MemberId(Long id, Long memberId);

    // 탈퇴 확인: 지갑에 주어진 상태의 충전이 하나라도 있는지 (idx_charge_wallet_id)
    boolean existsByWallet_IdAndStatusIn(Long walletId, Collection<ChargeStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Charge c where c.id = :id and c.wallet.memberId = :memberId")
    Optional<Charge> findByIdAndMemberIdForUpdate(@Param("id") Long id, @Param("memberId") Long memberId);

    // TX2에서 지갑을 먼저 잠그기 위해 Charge 엔티티를 불러오지 않고 회원 ID만 조회
    @Query("select c.wallet.memberId from Charge c where c.id = :id")
    Optional<Long> findMemberIdById(@Param("id") Long id);

    // 복구 스케줄러: 다음 조회 시각이 지난 PENDING 충전 한 건을 잠근다.
    // 여러 인스턴스가 동시에 돌아도 같은 건을 집지 않게 SKIP LOCKED. 부분 인덱스 idx_charge_recovery_due를 탄다
    @Query(value = """
            select id from charge
            where status = 'PENDING' and next_attempt_at <= :now
            order by next_attempt_at, id
            limit 1
            for update skip locked
            """, nativeQuery = true)
    Optional<Long> lockNextRecoveryTarget(@Param("now") LocalDateTime now);

    // 확보한 건에 임대를 건다: 커밋 뒤 잠금이 풀려도 임대가 끝날 때까지 다른 인스턴스의 대상이 아니다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Charge c set c.nextAttemptAt = :leasedUntil, c.updatedAt = :now where c.id = :id")
    int leaseRecovery(@Param("id") Long id,
                      @Param("leasedUntil") LocalDateTime leasedUntil,
                      @Param("now") LocalDateTime now);

    @Query("""
            select new com.justcommit.backend.payment.infrastructure.repository.ChargeRecoveryTarget(
                c.id, c.pgOrderNo, c.amount, c.createdAt, c.attemptCount, c.nextAttemptAt)
            from Charge c
            where c.id = :id
            """)
    Optional<ChargeRecoveryTarget> findRecoveryTarget(@Param("id") Long id);

    // 조회 결과 보류: 아직 PENDING이고, 확보할 때 읽은 attempt_count와 건 임대 값이 그대로일 때만 바꾼다.
    // 그사이 사용자 승인 등 다른 경로가 바꿨거나, 임대가 끝나 다른 인스턴스가 다시 잡았으면 0건
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Charge c
            set c.attemptCount = :attemptCount, c.nextAttemptAt = :nextAttemptAt, c.updatedAt = :now
            where c.id = :id
              and c.status = com.justcommit.backend.payment.domain.ChargeStatus.PENDING
              and c.attemptCount = :expectedAttemptCount
              and c.nextAttemptAt = :leasedUntil
            """)
    int deferRecovery(@Param("id") Long id,
                      @Param("expectedAttemptCount") int expectedAttemptCount,
                      @Param("leasedUntil") LocalDateTime leasedUntil,
                      @Param("attemptCount") int attemptCount,
                      @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                      @Param("now") LocalDateTime now);

    // 자동 복구 포기: REVIEW_REQUIRED로 바꾸고 더 조회하지 않는다. 조건은 deferRecovery와 같다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Charge c
            set c.status = com.justcommit.backend.payment.domain.ChargeStatus.REVIEW_REQUIRED,
                c.attemptCount = :attemptCount, c.nextAttemptAt = null, c.updatedAt = :now
            where c.id = :id
              and c.status = com.justcommit.backend.payment.domain.ChargeStatus.PENDING
              and c.attemptCount = :expectedAttemptCount
              and c.nextAttemptAt = :leasedUntil
            """)
    int markReviewRequired(@Param("id") Long id,
                           @Param("expectedAttemptCount") int expectedAttemptCount,
                           @Param("leasedUntil") LocalDateTime leasedUntil,
                           @Param("attemptCount") int attemptCount,
                           @Param("now") LocalDateTime now);
}
