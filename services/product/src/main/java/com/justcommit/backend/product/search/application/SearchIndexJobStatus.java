package com.justcommit.backend.product.search.application;

import com.justcommit.backend.product.search.domain.SearchIndexJob;
import com.justcommit.backend.product.search.domain.SearchIndexJobPhase;
import com.justcommit.backend.product.search.domain.SearchIndexJobType;
import java.time.LocalDateTime;

/**
 * 상품 검색 색인 작업의 진행 상태 (#563, GET admin/products/searchIndex/jobs/{jobId} 응답 · 작업 끝 이벤트).
 *
 * <p>processed · skipped · failed는 채우기(전체 색인) 단계, caughtUp은 재색인의 따라잡기 단계 결과(전체 색인은 null).
 * totalCount는 시작할 때 상품 행 수라 진행률은 processed / totalCount로 어림한다.
 */
public record SearchIndexJobStatus(Long jobId, SearchIndexJobType type, SearchIndexJobPhase phase,
                                   SearchIndexJobPhase failedPhase, String failureReason, String worker,
                                   String previousIndex, String nextIndex, Long totalCount,
                                   long processed, long skipped, long failed, Counts caughtUp,
                                   Boolean previousDeleted, LocalDateTime requestedAt, LocalDateTime startedAt,
                                   LocalDateTime heartbeatAt, LocalDateTime finishedAt) {

    public static SearchIndexJobStatus of(SearchIndexJob job) {
        Counts caughtUp = job.getType() == SearchIndexJobType.REBUILD
                ? new Counts(job.getCaughtUpProcessed(), job.getCaughtUpSkipped(), job.getCaughtUpFailed())
                : null;
        return new SearchIndexJobStatus(job.getId(), job.getType(), job.getPhase(), job.getFailedPhase(),
                job.getFailureReason(), job.getWorker(), job.getPreviousIndex(), job.getNextIndex(), job.getTotalCount(),
                job.getProcessedCount(), job.getSkippedCount(), job.getFailedCount(), caughtUp,
                job.getPreviousDeleted(), job.getCreatedAt(), job.getStartedAt(), job.getHeartbeatAt(),
                job.getFinishedAt());
    }

    public record Counts(long processed, long skipped, long failed) {
    }
}
