package com.justcommit.backend.product.search.infrastructure;

import com.justcommit.backend.product.search.domain.SearchIndexJob;
import com.justcommit.backend.product.search.domain.SearchIndexJobPhase;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 검색 색인 작업 기록 (#563). 진행 중 갱신은 모두 「아직 끝나지 않은 작업」(finishedAt이 null)에만 걸리는 조건부 UPDATE이고,
 * 바뀐 행 수(0이면 이미 끝난 작업)를 돌려준다.
 */
public interface SearchIndexJobRepository extends JpaRepository<SearchIndexJob, Long> {

    // 끝나지 않은 작업 중 하트비트가 threshold보다 오래된 것 (죽은 작업 후보)
    List<SearchIndexJob> findByFinishedAtIsNullAndHeartbeatAtBefore(LocalDateTime threshold);

    // 최근 작업 (409를 받았을 때 진행 중인 작업 확인용)
    List<SearchIndexJob> findTop10ByOrderByIdDesc();

    // 실행자가 등록된 작업을 시작
    @Transactional
    @Modifying
    @Query("""
            update SearchIndexJob j
               set j.phase = com.justcommit.backend.product.search.domain.SearchIndexJobPhase.FILLING,
                   j.worker = :worker, j.totalCount = :totalCount, j.startedAt = :now, j.heartbeatAt = :now
             where j.id = :id and j.finishedAt is null
               and j.phase = com.justcommit.backend.product.search.domain.SearchIndexJobPhase.QUEUED
            """)
    int start(@Param("id") Long id, @Param("worker") String worker, @Param("totalCount") long totalCount,
              @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("""
            update SearchIndexJob j
               set j.phase = :phase, j.previousIndex = coalesce(:previousIndex, j.previousIndex),
                   j.nextIndex = coalesce(:nextIndex, j.nextIndex), j.heartbeatAt = :now
             where j.id = :id and j.finishedAt is null
            """)
    int moveTo(@Param("id") Long id, @Param("phase") SearchIndexJobPhase phase,
               @Param("previousIndex") String previousIndex, @Param("nextIndex") String nextIndex,
               @Param("now") LocalDateTime now);

    // 채우기(전체 색인) 배치 하나의 결과를 더하고 하트비트 갱신
    @Transactional
    @Modifying
    @Query("""
            update SearchIndexJob j
               set j.processedCount = j.processedCount + :processed, j.skippedCount = j.skippedCount + :skipped,
                   j.failedCount = j.failedCount + :failed, j.heartbeatAt = :now
             where j.id = :id and j.finishedAt is null
            """)
    int addProgress(@Param("id") Long id, @Param("processed") long processed, @Param("skipped") long skipped,
                    @Param("failed") long failed, @Param("now") LocalDateTime now);

    // 따라잡기 배치 하나의 결과를 더하고 하트비트 갱신
    @Transactional
    @Modifying
    @Query("""
            update SearchIndexJob j
               set j.caughtUpProcessed = j.caughtUpProcessed + :processed,
                   j.caughtUpSkipped = j.caughtUpSkipped + :skipped,
                   j.caughtUpFailed = j.caughtUpFailed + :failed, j.heartbeatAt = :now
             where j.id = :id and j.finishedAt is null
            """)
    int addCatchUpProgress(@Param("id") Long id, @Param("processed") long processed, @Param("skipped") long skipped,
                           @Param("failed") long failed, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("""
            update SearchIndexJob j
               set j.phase = com.justcommit.backend.product.search.domain.SearchIndexJobPhase.COMPLETED,
                   j.previousDeleted = :previousDeleted, j.finishedAt = :now, j.heartbeatAt = :now
             where j.id = :id and j.finishedAt is null
            """)
    int complete(@Param("id") Long id, @Param("previousDeleted") Boolean previousDeleted,
                 @Param("now") LocalDateTime now);

    // 실패: 그때 단계를 failedPhase에 남김 (SET의 오른쪽은 모두 바꾸기 전 값)
    @Transactional
    @Modifying
    @Query("""
            update SearchIndexJob j
               set j.failedPhase = j.phase,
                   j.phase = com.justcommit.backend.product.search.domain.SearchIndexJobPhase.FAILED,
                   j.failureReason = :reason, j.finishedAt = :now
             where j.id = :id and j.finishedAt is null
            """)
    int fail(@Param("id") Long id, @Param("reason") String reason, @Param("now") LocalDateTime now);

    // 죽은 작업 실패 처리: 확인하는 사이 하트비트가 갱신됐으면(살아 있음) 바꾸지 않음
    @Transactional
    @Modifying
    @Query("""
            update SearchIndexJob j
               set j.failedPhase = j.phase,
                   j.phase = com.justcommit.backend.product.search.domain.SearchIndexJobPhase.FAILED,
                   j.failureReason = :reason, j.finishedAt = :now
             where j.id = :id and j.finishedAt is null and j.heartbeatAt < :threshold
            """)
    int failIfStale(@Param("id") Long id, @Param("threshold") LocalDateTime threshold, @Param("reason") String reason,
                    @Param("now") LocalDateTime now);
}
