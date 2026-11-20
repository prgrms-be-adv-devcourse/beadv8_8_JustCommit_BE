package com.justcommit.backend.settlement.infrastructure.repository;

import com.justcommit.backend.settlement.domain.Settlement;
import com.justcommit.backend.settlement.domain.SettlementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findBySellerOrderId(Long sellerOrderId);

    List<Settlement> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

    // 정산 지급 대상 한 건: 다음 시도 시각이 지난 PENDING · FAILED (idx_settlement_payout_due).
    // 다른 실행이 잡고 있는 행은 건너뛴다. 호출한 트랜잭션 안에서 임대한 뒤 바로 커밋한다 (#429)
    @Query(value = """
            SELECT *
              FROM settlement
             WHERE status IN ('PENDING', 'FAILED') AND next_attempt_at <= :now
             ORDER BY next_attempt_at
             LIMIT 1
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Settlement> findNextPayoutTargetForUpdate(@Param("now") LocalDateTime now);

    // 지급 결과 반영용 행 잠금. 잠근 뒤 상태 · 임대를 확인하고 바꾼다 (중복 반영 방지)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Settlement s WHERE s.id = :id")
    Optional<Settlement> findByIdForUpdate(@Param("id") Long id);

    // 지급 지연 감시: createdBefore 전에 만들어졌는데 아직 미지급인 정산 수
    long countByStatusInAndCreatedAtBefore(Collection<SettlementStatus> statuses, LocalDateTime createdBefore);

    // 탈퇴 확인: 판매자의 정산 중 statuses 상태인 것의 수 (idx_settlement_seller_id_created_at의 seller_id로 좁힘)
    long countBySellerIdAndStatusIn(Long sellerId, Collection<SettlementStatus> statuses);

    // 지급 지연 감시: 가장 오래된 미지급 정산
    Optional<Settlement> findFirstByStatusInOrderByCreatedAtAsc(Collection<SettlementStatus> statuses);
}
