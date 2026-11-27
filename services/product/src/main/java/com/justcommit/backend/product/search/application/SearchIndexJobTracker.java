package com.justcommit.backend.product.search.application;

import com.justcommit.backend.product.search.domain.SearchIndexJob;
import com.justcommit.backend.product.search.domain.SearchIndexJobPhase;
import com.justcommit.backend.product.search.infrastructure.SearchIndexJobRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 색인 작업 기록을 바꾸는 곳 (#563). 진행 중 갱신은 끝나지 않은 작업에만 걸리고, 끝내는 것(완료 · 실패)은 모두
 * {@link #finished}를 거쳐 로그와 {@link SearchIndexJobFinishedEvent}를 한 번만 낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexJobTracker {

    private final SearchIndexJobRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // 실행자가 등록된 작업을 시작. false면 그사이 실패 처리됨(실행하지 않음)
    public boolean start(Long jobId, String worker, long totalCount) {
        return repository.start(jobId, worker, totalCount, now()) == 1;
    }

    public JobProgress progress(Long jobId, SearchIndexJobPhase phase) {
        return new JobProgress(jobId, phase);
    }

    public void complete(Long jobId, Boolean previousDeleted) {
        if (repository.complete(jobId, previousDeleted, now()) == 1) {
            finished(jobId);
        }
    }

    public void fail(Long jobId, String reason) {
        if (repository.fail(jobId, truncate(reason), now()) == 1) {
            finished(jobId);
        }
    }

    // 하트비트가 threshold보다 오래된 작업을 실패 처리. 다른 서버가 먼저 처리했거나 그사이 살아났으면 false
    public boolean failIfStale(Long jobId, LocalDateTime threshold, String reason) {
        if (repository.failIfStale(jobId, threshold, truncate(reason), now()) == 1) {
            finished(jobId);
            return true;
        }
        return false;
    }

    // 작업 결과를 다루는 한 곳: 결과 로그 + 끝 이벤트 (#638 Slack 알림이 구독)
    private void finished(Long jobId) {
        repository.findById(jobId).map(SearchIndexJobStatus::of).ifPresent(status -> {
            if (status.phase() == SearchIndexJobPhase.FAILED) {
                log.warn("상품 검색 색인 작업 실패: {}", status);
            } else {
                log.info("상품 검색 색인 작업 완료: {}", status);
            }
            eventPublisher.publishEvent(new SearchIndexJobFinishedEvent(status));
        });
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= SearchIndexJob.FAILURE_REASON_LENGTH) {
            return reason;
        }
        return reason.substring(0, SearchIndexJob.FAILURE_REASON_LENGTH);
    }

    /**
     * 한 작업의 진행 기록. 배치마다 처리 수를 더하며 하트비트를 갱신하고, 작업이 이미 끝난 것으로 기록돼 있으면
     * {@link SearchIndexJobAbandonedException}으로 실행을 멈춘다. 한 실행 스레드에서만 쓴다.
     */
    public final class JobProgress implements IndexProgress {

        private final Long jobId;
        private SearchIndexJobPhase phase;
        private String previousIndex;
        private String nextIndex;

        private JobProgress(Long jobId, SearchIndexJobPhase phase) {
            this.jobId = jobId;
            this.phase = phase;
        }

        @Override
        public void phase(SearchIndexJobPhase phase, String previousIndex, String nextIndex) {
            fence(repository.moveTo(jobId, phase, previousIndex, nextIndex, now()));
            this.phase = phase;
            this.previousIndex = previousIndex;
            this.nextIndex = nextIndex;
        }

        @Override
        public void batch(int processed, int skipped, int failed) {
            int updated = phase == SearchIndexJobPhase.CATCHING_UP
                    ? repository.addCatchUpProgress(jobId, processed, skipped, failed, now())
                    : repository.addProgress(jobId, processed, skipped, failed, now());
            fence(updated);
        }

        public SearchIndexJobPhase phase() {
            return phase;
        }

        public String previousIndex() {
            return previousIndex;
        }

        public String nextIndex() {
            return nextIndex;
        }

        private void fence(int updated) {
            if (updated == 0) {
                throw new SearchIndexJobAbandonedException(jobId);
            }
        }
    }
}
