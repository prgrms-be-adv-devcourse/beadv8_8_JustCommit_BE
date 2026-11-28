package com.justcommit.backend.product.search.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 검색 전체 색인 · 무중단 재색인 작업 기록 (#563, V15). 작업마다 한 행.
 *
 * <p>끝나지 않은 행(finishedAt이 null)은 전체에서 하나만 둘 수 있어 이 행이 서버 간 잠금 역할을 한다.
 * 진행 중 갱신(단계 · 처리 수 · 하트비트)은 엔티티를 거치지 않고 저장소의 조건부 UPDATE로 한다
 * (끝난 작업은 바꾸지 않아, 죽은 작업으로 실패 처리된 뒤 늦게 깨어난 실행이 기록을 덮지 못하게).
 */
@Entity
@Table(name = "search_index_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchIndexJob {

    // 실패 이유 컬럼 길이
    public static final int FAILURE_REASON_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SearchIndexJobType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SearchIndexJobPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(name = "failed_phase", length = 20)
    private SearchIndexJobPhase failedPhase;

    @Column(length = 100)
    private String worker;

    @Column(name = "previous_index", length = 50)
    private String previousIndex;

    @Column(name = "next_index", length = 50)
    private String nextIndex;

    @Column(name = "total_count")
    private Long totalCount;

    @Column(name = "processed_count", nullable = false)
    private long processedCount;

    @Column(name = "skipped_count", nullable = false)
    private long skippedCount;

    @Column(name = "failed_count", nullable = false)
    private long failedCount;

    @Column(name = "caught_up_processed", nullable = false)
    private long caughtUpProcessed;

    @Column(name = "caught_up_skipped", nullable = false)
    private long caughtUpSkipped;

    @Column(name = "caught_up_failed", nullable = false)
    private long caughtUpFailed;

    @Column(name = "previous_deleted")
    private Boolean previousDeleted;

    @Column(name = "failure_reason", length = FAILURE_REASON_LENGTH)
    private String failureReason;

    @Column(name = "heartbeat_at", nullable = false)
    private LocalDateTime heartbeatAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 재색인: 등록만 하고 실행자가 시작할 때 FILLING
    public static SearchIndexJob queued(SearchIndexJobType type, String worker, LocalDateTime now) {
        SearchIndexJob job = new SearchIndexJob();
        job.type = type;
        job.phase = SearchIndexJobPhase.QUEUED;
        job.worker = worker;
        job.heartbeatAt = now;
        job.createdAt = now;
        return job;
    }

    // 전체 색인: 요청 스레드에서 바로 시작
    public static SearchIndexJob started(SearchIndexJobType type, String worker, long totalCount, LocalDateTime now) {
        SearchIndexJob job = queued(type, worker, now);
        job.phase = SearchIndexJobPhase.FILLING;
        job.totalCount = totalCount;
        job.startedAt = now;
        return job;
    }
}
