package com.justcommit.backend.member.unlink.infrastructure.repository;

import com.justcommit.backend.member.unlink.domain.KakaoUnlinkReceipt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

// 카카오 연결 해제 웹훅 수신 기록 (#422). 처리용 조회 · 확보 쿼리는 처리 단계를 만들 때 추가
public interface KakaoUnlinkReceiptRepository extends JpaRepository<KakaoUnlinkReceipt, Long> {

  // 처리할 기록 한 건(PENDING · INACTIVATED · NOTICE_PENDING): 다음 시도 시각이 지났고 임대 중이 아닌 것. 파드가 여러 개여도 겹쳐 집지 않음
  @Query(value = """
      SELECT *
        FROM member_kakao_unlink_receipt
       WHERE status IN ('PENDING', 'INACTIVATED', 'NOTICE_PENDING')
         AND next_attempt_at <= clock_timestamp()
         AND (lease_until IS NULL OR lease_until <= clock_timestamp())
       ORDER BY next_attempt_at
       LIMIT 1
         FOR UPDATE SKIP LOCKED
      """, nativeQuery = true)
  Optional<KakaoUnlinkReceipt> findNextWorkForUpdate();

  // 같은 회원 · 같은 연결 해제 상태의 자동 탈퇴 처리 기록 (부분 UNIQUE라 최대 하나)
  Optional<KakaoUnlinkReceipt> findByMemberIdAndUnlinkVersionAndHandlesWithdrawalTrue(Long memberId,
                                                                                     Integer unlinkVersion);

  // 결과 반영 전에 기록 행을 잠근다 (처리권 · 단계 확인 → 변경을 한 트랜잭션에서)
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from KakaoUnlinkReceipt r where r.id = :id")
  Optional<KakaoUnlinkReceipt> findByIdForUpdate(@Param("id") Long id);
}
