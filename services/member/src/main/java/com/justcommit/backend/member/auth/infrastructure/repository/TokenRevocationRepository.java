package com.justcommit.backend.member.auth.infrastructure.repository;

import com.justcommit.backend.member.auth.domain.TokenRevocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 회원 토큰 폐기 작업 (#409).
 * 상태 갱신은 조회 후 수정이 아니라 조건부 UPDATE로 한다: 쓰는 순간 DB가 PENDING인지 확인하므로
 * 최초 기록과 재처리가 같은 작업을 동시에 다뤄도, 늦게 끝난 쪽이 이미 RECORDED · EXPIRED인 작업을 되돌리지 못한다.
 * 반환값은 바뀐 행 수 (0 = 이미 PENDING이 아니거나 임대가 바뀌어 건드리지 않음).
 * updated_at은 엔티티 감사(@LastModifiedDate)와 같은 기준(앱 시각 LocalDateTime)으로 넘긴다.
 */
public interface TokenRevocationRepository extends JpaRepository<TokenRevocation, Long> {

  // 재처리 대상 한 건: 다음 시도 시각이 지난 PENDING (보관 기한이 지난 작업도 집어 Recorder가 EXPIRED로 끝냄)
  // 다른 실행이 잡고 있는 행은 건너뛴다. 호출한 트랜잭션 안에서 임대한 뒤 바로 커밋한다
  @Query(value = """
      SELECT *
        FROM member_token_revocation
      WHERE status = 'PENDING' AND next_attempt_at <= clock_timestamp()
      ORDER BY next_attempt_at
      LIMIT 1
        FOR UPDATE SKIP LOCKED
      """, nativeQuery = true)
  Optional<TokenRevocation> findNextRetryTargetForUpdate();

  // 회원의 가장 늦은 폐기 시각 (#546). 폐기 기록이 없으면 비어 있음
  // 상태와 관계없이 본다: PENDING도 재처리가 나중에 Redis에 기록하므로 발급 시각은 그 뒤여야 한다
  @Query("select max(r.cutoffAt) from TokenRevocation r where r.memberId = :memberId")
  Optional<Instant> findLatestCutoffAt(@Param("memberId") Long memberId);

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

  // 기록 실패: 이 실행이 잡은 임대(expectedNextAttemptAt)가 그대로일 때만 바꾼다
  // 다음 시도는 실패 시점(DB 시각) 기준 실패 횟수에 따라 +10초 → +30초 → 이후 +1분 (택배 예약 큐와 같은 간격)
  // CASE의 attempt_count는 이번 실패를 더하기 전 값이다
  @Modifying
  @Query(value = """
      UPDATE member_token_revocation
        SET attempt_count = attempt_count + 1,
              last_error = :error,
              next_attempt_at = clock_timestamp() + CASE attempt_count
                        WHEN 0 THEN INTERVAL '10 seconds'
                        WHEN 1 THEN INTERVAL '30 seconds'
                        ELSE INTERVAL '1 minute'
                      END,
              updated_at = :updatedAt
      WHERE id = :id AND status = 'PENDING' AND next_attempt_at = :expectedNextAttemptAt
      """, nativeQuery = true)
  int recordFailure(@Param("id") Long id, @Param("error") String error,
                    @Param("expectedNextAttemptAt") Instant expectedNextAttemptAt,
                    @Param("updatedAt") LocalDateTime updatedAt);
}
