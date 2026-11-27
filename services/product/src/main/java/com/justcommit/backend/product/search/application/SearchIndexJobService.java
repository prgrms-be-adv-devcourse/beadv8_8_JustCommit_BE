package com.justcommit.backend.product.search.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.search.application.ProductIndexService.RebuildResult;
import com.justcommit.backend.product.search.application.ProductIndexService.ReindexResult;
import com.justcommit.backend.product.search.application.SearchIndexJobTracker.JobProgress;
import com.justcommit.backend.product.search.domain.SearchIndexJob;
import com.justcommit.backend.product.search.domain.SearchIndexJobPhase;
import com.justcommit.backend.product.search.domain.SearchIndexJobType;
import com.justcommit.backend.product.search.infrastructure.SearchIndexJobRepository;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 상품 검색 전체 색인 · 무중단 재색인을 작업으로 기록하고 실행한다 (#563, docs/flows/search-recommend.md 「매핑 변경」).
 *
 * <ul>
 *   <li>잠금: 작업 행을 INSERT할 때 「끝나지 않은 작업은 하나만」 유니크 인덱스(V15)가 걸려, 다른 서버에서 동시에 요청해도
 *       하나만 등록되고 나머지는 409 SEARCH_INDEXING_IN_PROGRESS. 잠금을 쥔 동안 DB 연결을 잡고 있지 않는다
 *       (Postgres advisory lock은 세션 잠금이라 작업 내내 연결 하나를 잡아야 해서 쓰지 않음).</li>
 *   <li>죽은 작업: 실행 중인 서버는 배치마다 하트비트를 갱신한다. staleAfter 동안 갱신이 없으면 서버가 죽은 것으로 보고
 *       다음 요청 · 정리 스케줄러가 실패 처리해 잠금을 푼다 ({@link #failStaleJobs}).</li>
 *   <li>전체 색인은 지금처럼 요청 스레드에서 실행하고 결과를 응답하며, 재색인은 등록만 하고 별도 실행자에서 돈다.</li>
 * </ul>
 */
@Slf4j
@Service
public class SearchIndexJobService {

    private final SearchIndexJobRepository repository;
    private final SearchIndexJobTracker tracker;
    private final ProductIndexService indexService;
    private final ProductRepository productRepository;
    private final ProductSearchIndex searchIndex;
    private final TaskExecutor executor;
    private final Clock clock;
    private final Duration staleAfter;
    private final String worker;

    public SearchIndexJobService(SearchIndexJobRepository repository, SearchIndexJobTracker tracker,
                                 ProductIndexService indexService, ProductRepository productRepository,
                                 ProductSearchIndex searchIndex,
                                 @Qualifier("searchIndexJobExecutor") TaskExecutor executor, Clock clock,
                                 @Value("${custom.search.index-job.stale-after:PT2M}") Duration staleAfter,
                                 @Value("${custom.search.index-job.worker:}") String worker) {
        this.repository = repository;
        this.tracker = tracker;
        this.indexService = indexService;
        this.productRepository = productRepository;
        this.searchIndex = searchIndex;
        this.executor = executor;
        this.clock = clock;
        this.staleAfter = staleAfter;
        this.worker = worker.isBlank() ? hostName() : worker;
    }

    // 무중단 재색인 등록: 작업 행만 남기고 바로 돌려줌 (202). 실제 재색인은 실행자에서
    public SearchIndexJobStatus requestRebuild() {
        failStaleJobs();
        SearchIndexJob job = register(SearchIndexJob.queued(SearchIndexJobType.REBUILD, worker, now()));
        try {
            executor.execute(() -> runRebuild(job.getId()));
        } catch (TaskRejectedException e) {
            // 같은 서버에서 실패 처리된 옛 실행이 아직 실행자를 차지하고 있을 때
            tracker.fail(job.getId(), "실행자가 아직 이전 작업을 실행 중이라 시작하지 못함");
            throw new BusinessException(ProductErrorCode.SEARCH_INDEXING_IN_PROGRESS);
        }
        log.info("상품 검색 무중단 재색인 등록: jobId={}, worker={}", job.getId(), worker);
        return SearchIndexJobStatus.of(job);
    }

    // 전체 색인: 작업 행으로 잠그고 요청 스레드에서 실행, 결과를 그대로 응답
    public ReindexResult reindexAll() {
        failStaleJobs();
        Long jobId = register(SearchIndexJob.started(SearchIndexJobType.REINDEX_ALL, worker,
                productRepository.count(), now())).getId();
        JobProgress progress = tracker.progress(jobId, SearchIndexJobPhase.FILLING);
        try {
            ReindexResult result = indexService.reindexAll(progress);
            tracker.complete(jobId, null);
            return result;
        } catch (RuntimeException e) {
            tracker.fail(jobId, reason(e, progress));
            throw e;
        }
    }

    public SearchIndexJobStatus status(Long jobId) {
        return repository.findById(jobId).map(SearchIndexJobStatus::of)
                .orElseThrow(() -> new BusinessException(ProductErrorCode.SEARCH_INDEX_JOB_NOT_FOUND));
    }

    public List<SearchIndexJobStatus> recent() {
        return repository.findTop10ByOrderByIdDesc().stream().map(SearchIndexJobStatus::of).toList();
    }

    /**
     * 하트비트가 staleAfter보다 오래 끊긴 작업을 실패 처리한다 (서버가 죽어 멈춘 작업의 잠금 풀기).
     * 새 인덱스를 채우던 재색인이면 alias가 아직 옮겨지지 않은 새 인덱스를 지워, 다음 재색인이 같은 이름으로 다시 만들 수 있게 한다.
     * 다음 요청과 정리 스케줄러가 부르고, 여러 서버가 동시에 불러도 실패 처리는 한 곳만 한다.
     */
    public void failStaleJobs() {
        LocalDateTime threshold = now().minus(staleAfter);
        for (SearchIndexJob job : repository.findByFinishedAtIsNullAndHeartbeatAtBefore(threshold)) {
            String reason = "하트비트가 " + staleAfter.toSeconds() + "초 넘게 끊겨 실행 서버(" + job.getWorker()
                    + ")가 멈춘 것으로 보고 실패 처리" + afterSwitchNote(job.getPhase(), job.getPreviousIndex(),
                    job.getNextIndex());
            if (tracker.failIfStale(job.getId(), threshold, reason)) {
                discardAbandonedIndex(job);
            }
        }
    }

    private SearchIndexJob register(SearchIndexJob job) {
        try {
            return repository.saveAndFlush(job);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ProductErrorCode.SEARCH_INDEXING_IN_PROGRESS);
        }
    }

    private void runRebuild(Long jobId) {
        if (!tracker.start(jobId, worker, productRepository.count())) {
            log.warn("상품 검색 재색인 작업 {}가 시작 전에 끝난 것으로 기록돼 실행하지 않음", jobId);
            return;
        }
        JobProgress progress = tracker.progress(jobId, SearchIndexJobPhase.FILLING);
        try {
            RebuildResult result = indexService.rebuild(progress);
            tracker.complete(jobId, result.previousDeleted());
        } catch (SearchIndexJobAbandonedException e) {
            log.warn(e.getMessage());
        } catch (RuntimeException e) {
            log.error("상품 검색 무중단 재색인 실패: jobId={}", jobId, e);
            tracker.fail(jobId, reason(e, progress));
        }
    }

    // 채우던 중 멈춘 재색인의 새 인덱스 지우기. alias가 이미 옮겨졌으면 지우지 않음
    private void discardAbandonedIndex(SearchIndexJob job) {
        boolean beforeSwitch = job.getPhase() == SearchIndexJobPhase.FILLING
                || job.getPhase() == SearchIndexJobPhase.SWITCHING_ALIAS;
        if (job.getType() != SearchIndexJobType.REBUILD || job.getNextIndex() == null || !beforeSwitch) {
            return;
        }
        try {
            if (!job.getNextIndex().equals(searchIndex.currentIndex())) {
                searchIndex.deleteIndex(job.getNextIndex());
                log.info("멈춘 재색인 작업 {}의 새 인덱스 {}를 지움", job.getId(), job.getNextIndex());
            }
        } catch (RuntimeException e) {
            log.warn("멈춘 재색인 작업 {}의 새 인덱스 {}를 지우지 못함 (다음 재색인 전에 직접 지워야 함)",
                    job.getId(), job.getNextIndex(), e);
        }
    }

    private static String reason(RuntimeException e, JobProgress progress) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message + afterSwitchNote(progress.phase(), progress.previousIndex(), progress.nextIndex());
    }

    // alias를 옮긴 뒤 실패했으면 옛 인덱스가 남아 있을 수 있음을 알림
    private static String afterSwitchNote(SearchIndexJobPhase phase, String previous, String next) {
        if (phase == SearchIndexJobPhase.CATCHING_UP || phase == SearchIndexJobPhase.CLEANING_UP) {
            return " (alias는 이미 " + next + "로 옮김. 전체 색인을 다시 실행해 맞춘 뒤 옛 인덱스 " + previous
                    + "가 남아 있으면 직접 지움)";
        }
        return "";
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String hostName() {
        String host = System.getenv("HOSTNAME");
        if (host != null && !host.isBlank()) {
            return host;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
