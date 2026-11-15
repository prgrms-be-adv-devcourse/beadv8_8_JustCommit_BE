package com.justcommit.backend.member.auth.infrastructure.repository;

import com.justcommit.backend.member.auth.domain.TokenRevocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 회원 토큰 폐기 작업 (#409).
 * 상태 갱신은 조회 후 수정이 아니라 조건부 UPDATE로 한다: 쓰는 순간 DB가 PENDING인지 확인하므로
 * 최초 기록과 재처리가 같은 작업을 동시에 다뤄도, 늦게 끝난 쪽이 이미 RECORDED · EXPIRED인 작업을 되돌리지 못한다.
 * 반환값은 바뀐 행 수 (0 = 이미 PENDING이 아니라 건드리지 않음).
 * updated_at은 엔티티 감사(@LastModifiedDate)와 같은 기준(앱 시각 LocalDateTime)으로 넘긴다.
 */
public interface TokenRevocationRepository extends JpaRepository<TokenRevocation, Long> {

  @Modifying
  @Query(value = """
      UPDATE member_token_revocation
        SET status = 'RECORDED', next_attempt_at = NULL, last_error = NULL, updated_at = :updatedAt
      WHERE id = :id AND status = 'PENDING'
      """, nativeQuery = true)
  int markRecorded(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);

  @Modifying
  @Query(value = """
      UPDATE member_token_revocation
        SET status = 'EXPIRED', next_attempt_at = NULL, updated_at = :updatedAt
      WHERE id = :id AND status = 'PENDING'
      """, nativeQuery = true)
  int markExpired(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);

  // 다음 시도 시각은 SQL 안에서 DB 시각으로 계산한다 (앱이 DB 시각을 읽지 못한 경우에도 기록 가능)
  @Modifying
  @Query(value = """
      UPDATE member_token_revocation
        SET attempt_count = attempt_count + 1,
            last_error = :error,
            next_attempt_at = clock_timestamp() + make_interval(secs => CAST(:retryDelaySeconds AS double precision)),
            updated_at = :updatedAt
      WHERE id = :id AND status = 'PENDING'
      """, nativeQuery = true)
  int recordFailure(@Param("id") Long id, @Param("error") String error,
                    @Param("retryDelaySeconds") long retryDelaySeconds,
                    @Param("updatedAt") LocalDateTime updatedAt);
}
